package com.twitter.zipkin.tracegen

/*
 * Copyright 2014 Twitter Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
import com.twitter.app.App
import com.twitter.finagle.Thrift
import com.twitter.logging.Logger
import com.twitter.scrooge.BinaryThriftStructSerializer
import com.twitter.util.{Await, Future}
import com.twitter.zipkin.common.Span
import com.twitter.zipkin.conversions.thrift._
import com.twitter.zipkin.{gen => thrift}
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * Generates a couple of semi-realistic traces and
 * sends to the collector. It's all pretty hacky.
 */
object Main extends App with ZipkinSpanGenerator {
  val scribeDest = flag("scribeDest", "localhost:9410", "Destination of the collector")
  val queryDest = flag("queryDest", "localhost:9411", "Destination of the query service")
  val generateOnly = flag("generateOnly", false, "Only generate date, do not request it back")

  private[this] val serializer = new BinaryThriftStructSerializer[thrift.Span] { def codec = thrift.Span }

  def main() {
    val scribe = Thrift.newIface[thrift.Scribe[Future]](scribeDest())
    val store = { spans: Seq[Span] =>
      scribe.log(spans.map { span => thrift.LogEntry("zipkin", serializer.toString(span.toThrift)) }).unit
    }
    Await.result(generateTraces(store))

    if (!generateOnly()) {
      val client = Thrift.newIface[thrift.ZipkinQuery[Future]](queryDest())
      Await.result {
        querySpan(
          client,
          "vitae",
          "velit",
          "some custom annotation",
          ("key", ByteBuffer.wrap("value".getBytes)),
          10)
      }
    }
  }

  private[this] def printTrace(traceIds: Seq[Long], client: thrift.ZipkinQuery[Future]): Future[Unit] = {
    client.getTracesByIds(traceIds, List(thrift.Adjust.TimeSkew)) map { traces =>
      for (trace <- traces; span <- trace.spans) yield
        println("Got span: " + span)
    }
  }

  private[this] def querySpan(
    client: thrift.ZipkinQuery[Future],
    service: String,
    span: String,
    annotation: String,
    kvAnnotation: (String, ByteBuffer),
    maxTraces: Int
  ): Future[Unit] = {
    println("Querying for service name: " + service + " and span name " + span)
    for {
      ts1 <- client.getTraceIdsBySpanName(service, span, Long.MaxValue, maxTraces, thrift.Order.DurationDesc)
      _ = printTrace(ts1, client)

      _ = println("Querying for service name: " + service)
      ts2 <- client.getTraceIdsBySpanName(service, "", Long.MaxValue, maxTraces, thrift.Order.DurationDesc)
      _ <- printTrace(ts2, client)

      _ = println("Querying for annotation: " + annotation)
      ts3 <- client.getTraceIdsByAnnotation(service, annotation, ByteBuffer.wrap("".getBytes), Long.MaxValue, maxTraces, thrift.Order.DurationDesc)
      _ <- printTrace(ts3, client)

      _ = println("Querying for kv annotation: " + kvAnnotation)
      ts4 <- client.getTraceIdsByAnnotation(service, kvAnnotation._1, kvAnnotation._2, Long.MaxValue, maxTraces, thrift.Order.DurationDesc)
      _ <- printTrace(ts4, client)

      traces <- client.getTracesByIds(ts4, List(thrift.Adjust.TimeSkew))
      _ = println(traces.toString)

      traceTimeline <- client.getTraceTimelinesByIds(ts4, List(thrift.Adjust.TimeSkew))
      _ = println("Timeline:")
      _ = println(traceTimeline.toString)

      ttl <- client.getDataTimeToLive()
      _ = println("Data ttl: " + ttl)

      svcNames <- client.getServiceNames()
      _ = println("Service names: " + svcNames)

      spanNames <- client.getSpanNames(service)
      _ = println("Span names for : " + service + " " + spanNames)
    } yield ()
  }
}

trait ZipkinSpanGenerator { self: App =>
  val genTraces = flag("genTraces", 5, "Number of traces to generate")
  val maxDepth = flag("maxDepth", 7, "Max depth of generated traces")

  def generateTraces(store: Seq[Span] => Future[Unit]): Future[Unit] = {
    val traceGen = new TraceGen(genTraces(), maxDepth())
    store(traceGen())
  }
}
