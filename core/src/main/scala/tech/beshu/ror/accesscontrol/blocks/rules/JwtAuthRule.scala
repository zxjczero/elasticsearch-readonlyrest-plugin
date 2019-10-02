/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.accesscontrol.blocks.rules

import cats.implicits._
import io.jsonwebtoken.Jwts
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef.SignatureCheckMethod._
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthenticationRule, AuthorizationRule, NoImpersonationSupport, RuleResult}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.request.RequestContextOps._
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.ClaimSearchResult.{Found, NotFound}
import tech.beshu.ror.accesscontrol.utils.ClaimsOps._
import tech.beshu.ror.utils.LoggerOps._
import tech.beshu.ror.utils.SecureStringHasher
import tech.beshu.ror.utils.SecureStringHasher.Algorithm
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import scala.util.Try

class JwtAuthRule(val settings: JwtAuthRule.Settings)
  extends AuthenticationRule
    with NoImpersonationSupport
    with AuthorizationRule
    with Logging {

  override val name: Rule.Name = JwtAuthRule.name

  private val parser = settings.jwt.checkMethod match {
    case NoCheck(_) => Jwts.parser
    case Hmac(rawKey) => Jwts.parser.setSigningKey(rawKey)
    case Rsa(pubKey) => Jwts.parser.setSigningKey(pubKey)
    case Ec(pubKey) => Jwts.parser.setSigningKey(pubKey)
  }

  private val hasher = new SecureStringHasher(Algorithm.Sha256)

  override def tryToAuthenticate(requestContext: RequestContext,
                                 blockContext: BlockContext): Task[RuleResult] = Task
    .unit
    .flatMap { _ =>
      jwtTokenFrom(requestContext) match {
        case None =>
          logger.debug(s"Authorization header '${settings.jwt.authorizationTokenDef.headerName.show}' is missing or does not contain a JWT token")
          Task.now(Rejected())
        case Some(token) =>
          process(token, blockContext)
      }
    }

  private def jwtTokenFrom(requestContext: RequestContext) = {
    requestContext
        .authorizationToken(settings.jwt.authorizationTokenDef)
        .map(t => JwtToken(t.value))
  }

  private def process(token: JwtToken, blockContext: BlockContext) = {
    userAndGroupsFromJwtToken(token) match {
      case Left(_) =>
        Task.now(Rejected())
      case Right((tokenPayload, user, groups)) =>
        logger.debug(s"JWT resolved user for claim ${settings.jwt.userClaim}: $user, and groups for claim ${settings.jwt.groupsClaim}: $groups")
        val claimProcessingResult = for {
          newBlockContext <- handleUserClaimSearchResult(blockContext, user)
          finalBlockContext <- handleGroupsClaimSearchResult(newBlockContext, groups)
        } yield finalBlockContext.withJwt(tokenPayload)
        claimProcessingResult match {
          case Left(_) =>
            Task.now(Rejected())
          case Right(modifiedBlockContext) =>
            settings.jwt.checkMethod match {
              case NoCheck(service) =>
                service
                  .authenticate(Credentials(User.Id(hasher.hash(token.value)), PlainTextSecret(token.value)))
                  .map(RuleResult.fromCondition(modifiedBlockContext)(_))
              case Hmac(_) | Rsa(_) | Ec(_) =>
                Task.now(Fulfilled(modifiedBlockContext))
            }
        }
    }
  }

  private def userAndGroupsFromJwtToken(token: JwtToken) = {
    claimsFrom(token).map { decodedJwtToken =>
      (decodedJwtToken, userIdFrom(decodedJwtToken), groupsFrom(decodedJwtToken))
    }
  }

  private def badTokenLogger(ex: Throwable, token: JwtToken): Unit = {
    val tokenParts = token.show.split(".")
    val printableToken = if (!logger.delegate.isDebugEnabled && tokenParts.length == 3) {
      // signed JWT, last block is the cryptographic digest, which should be treated as a secret.
      s"${tokenParts(0)}.${tokenParts(1)} (omitted digest)"
    }
    else {
      token.show
    }
    logger.errorEx(s"JWT token '${printableToken}' parsing error: " + ex.getClass.getSimpleName + " " + ex.getMessage, ex)
  }

  private def claimsFrom(token: JwtToken) = {
    settings.jwt.checkMethod match {
      case NoCheck(_) =>
        token.value.value.split("\\.").toList match {
          case fst :: snd :: _ =>
            Try(parser.parseClaimsJwt(s"$fst.$snd.").getBody)
              .toEither
              .map(JwtTokenPayload.apply)
              .left.map { ex =>
                badTokenLogger(ex, token)
            }
          case _ =>
            Left(())
        }
      case Hmac(_) | Rsa(_) | Ec(_) =>
        Try(parser.parseClaimsJws(token.value.value).getBody)
          .toEither
          .map(JwtTokenPayload.apply)
          .left.map { ex =>
            badTokenLogger(ex, token)
        }
    }
  }

  private def userIdFrom(payload: JwtTokenPayload) = {
    settings.jwt.userClaim.map(payload.claims.userIdClaim)
  }

  private def groupsFrom(payload: JwtTokenPayload) = {
    settings.jwt.groupsClaim.map(payload.claims.groupsClaim)
  }

  private def handleUserClaimSearchResult(blockContext: BlockContext, result: Option[ClaimSearchResult[User.Id]]) = {
    result match {
      case None => Right(blockContext)
      case Some(Found(userId)) => Right(blockContext.withLoggedUser(DirectlyLoggedUser(userId)))
      case Some(NotFound) => Left(())
    }
  }

  private def handleGroupsClaimSearchResult(blockContext: BlockContext, result: Option[ClaimSearchResult[UniqueList[Group]]]) = {
    result match {
      case None if settings.groups.nonEmpty => Left(())
      case Some(NotFound) if settings.groups.nonEmpty => Left(())
      case Some(NotFound) => Right(blockContext) // if groups field is not found, we treat this situation as same as empty groups would be passed
      case Some(Found(groups)) if settings.groups.nonEmpty =>
        UniqueNonEmptyList.fromSortedSet(settings.groups.intersect(groups)) match {
          case Some(matchedGroups) => Right(blockContext.withAddedAvailableGroups(matchedGroups))
          case None => Left(())
        }
      case Some(Found(_)) | None => Right(blockContext)
    }
  }

}

object JwtAuthRule {
  val name = Rule.Name("jwt_auth")

  final case class Settings(jwt: JwtDef, groups: UniqueList[Group])

}