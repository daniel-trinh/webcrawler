package biz.http.client

import biz.concurrency.FutureImplicits._
import biz.crawler.url.{ AbsoluteUrl, CrawlerUrl }
import biz.CrawlerExceptions.{ MissingRedirectUrlException, RedirectLimitReachedException }
import biz.crawler.CrawlerAgents

import com.github.nscala_time.time.Imports.{ DateTime => DT }

import play.api.libs.concurrent.Execution.Implicits._

import spray.http._
import spray.client.pipelining._

import scala.async.Async.{ async, await }
import scala.concurrent.Future
import scala.util.{ Try, Success, Failure }

trait RedirectFollower {
  def originCrawlerUrl: CrawlerUrl

  /**
   * Follows redirects until a non 2xx response is reached or some sort of
   * error occurrs
   *
   * @param resUrl
   * @param res
   * @param maxRedirects
   * @return
   */
  def followRedirects(
    resUrl: CrawlerUrl,
    res: Future[HttpResponse],
    maxRedirects: Int = 5): Future[HttpResponse] = {

    // not tail recursive, but shouldn't be a problem because maxRedirect should be a low number.
    def followRedirects1(
      redirectUrl: CrawlerUrl,
      redirectResponse: Future[HttpResponse],
      redirectsLeft: Int): Future[HttpResponse] = {

      play.Logger.debug(s"Redirects left: $redirectsLeft : Time: ${DT.now}")

      //TODO: is there a better way of coding this without having a ridiculous amount of nesting?
      async {
        val response = await(redirectResponse)
        // Only continue trying to follow redirects if status code is 3xx
        val code = response.status.intValue
        if (code < 300 || code > 400) {
          await(redirectResponse)
        } else if (redirectsLeft <= 0) {
          await(Future.failed[HttpResponse](RedirectLimitReachedException(resUrl.fromUri.toString(), resUrl.uri.toString())))
        } else {
          // Find the Location header if one exists
          val maybeLocationHeader = response.headers.find { header =>
            header.lowercaseName == "location"
          }
          maybeLocationHeader match {
            case Some(header) => {
              val newUrl = header.value

              val nextRedirectUrl: CrawlerUrl = {
                if (newUrl.startsWith("http") || newUrl.startsWith("https")) {
                  AbsoluteUrl(redirectUrl.uri, Get(newUrl).uri)
                } else {
                  val absoluteUrl = s"${redirectUrl.uri.scheme}${redirectUrl.uri.authority}$newUrl"
                  AbsoluteUrl(redirectUrl.uri, Get(absoluteUrl).uri)
                }
              }

              // val followedRedirect: Future[HttpResponse] =

              play.Logger.debug("1")
              val followedRedirect: Future[HttpResponse] = async {
                val crawlerDomain = await(originCrawlerUrl.domain.asFuture)
                val nextRelativePath = nextRedirectUrl.uri.path.toString()
                val httpClient = CrawlerAgents.getClient(nextRedirectUrl.uri)
                play.Logger.debug("2")

                httpClient.get(nextRelativePath)

                val nextRedirectResponse = httpClient.get(nextRelativePath)
                play.Logger.debug("3")

                await(followRedirects1(nextRedirectUrl, nextRedirectResponse, redirectsLeft - 1))
              }
              play.Logger.debug("4")

              await(followedRedirect)
            }
            case None => {
              await(Future.failed[HttpResponse](MissingRedirectUrlException(redirectUrl.fromUri.toString(), "No URL found in redirect")))
            }
          }
        }
      }
    }

    async {
      val response = await(res)
      val code = response.status.intValue
      await(followRedirects1(resUrl, res, maxRedirects))
    }
  }

}
