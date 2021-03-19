package ftier
package http
package server

import zio.*, nio.*, core.*, core.channels.*
import zero.ext.*, option.*

import ws.*

sealed trait Protocol
case class Http(state: HttpState) extends Protocol
case class WsProto(state: WsState, ctx: WsContextData) extends Protocol

object Protocol {
  def http: Http = Http(HttpState())
  def ws(ctx: WsContextData): WsProto = WsProto(WsState(None, Chunk.empty), ctx)
}

type HttpHandler[R] = Request => ZIO[Has[R], Nothing, Response]
type WsHandler[R] = Msg => ZIO[WsContext & Has[R], Nothing, Unit]

def processHttp[R](
  ch: SocketChannel
, hth: HttpHandler[R]
, wsh: WsHandler[R]
)(
  protocol: Http
, chunk: Chunk[Byte]
): ZIO[Has[R], Throwable, Protocol] =
  val x1: IO[BadReq.type, HttpState] =
    http.processChunk(chunk, protocol.state)
  val x2: ZIO[Has[R], BadReq.type | BadReq.type, (Protocol, Option[Response])] =
    x1.flatMap{
      case s: MsgDone =>
        for {
          req  <- toReq(s.msg)
          resp <- hth(req)
        } yield {
          if (resp.code == 101) {
            val p =
              Protocol.ws(
                WsContextData(
                  req
                , msg => for {
                    bb <- write(msg).orDie
                    _ <- ch.write(bb).orDie
                  } yield unit
                , ch.close
                )
              )
            (p, resp.some)
          } else {
            val p = Protocol.http
            (p, resp.some)
          }
        }
      case s => 
        IO.succeed((protocol.copy(state=s), None))
    }
  val x3 = x2.catchAll{
    case BadReq => IO.succeed((Protocol.http, Response(400).some))
  }
  x3.flatMap{ 
    case (p, Some(resp)) if resp.code == 101 =>
      ch.write(build(resp)) *> IO.succeed(p)
    case (p, Some(resp)) =>
      ch.write(build(resp)) *> ch.close *> IO.succeed(p)
    case (p, None) =>
      IO.succeed(p)
  }.flatMap{
    case p: Http => IO.succeed(p)
    case p: WsProto =>
      val ctx: ULayer[WsContext] = ZLayer.succeed(p.ctx)
      for {
        _ <- wsh(Open).provideSomeLayer[Has[R]](ctx)
      } yield p
  }

def processWs[R](
  ch: SocketChannel
, wsh: WsHandler[R]
)(
  protocol: WsProto
, chunk: Chunk[Byte]
): ZIO[Has[R], Throwable, Protocol] =
  val state = protocol.state
  val newState = ws.parseHeader(state.copy(data=state.data ++ chunk))
  newState match
    case WsState(Some(h: WsHeader), chunk) if h.size <= chunk.length =>
      val (data, rem) = chunk.splitAt(h.size)
      val payload = processMask(h.mask, h.maskN, data)
      val msg = read(h.opcode, payload)
      val ctx: ULayer[WsContext] = ZLayer.succeed(protocol.ctx)
      for {
        _ <- wsh(msg).provideSomeLayer[Has[R]](ctx)
        r <- processWs(ch, wsh)(protocol.copy(state=WsState(None, rem)), Chunk.empty)
      } yield r
    case state => 
      IO.succeed(protocol.copy(state=state))

def httpProtocol[R](
  ch: SocketChannel
, hth: HttpHandler[R]
, wsh: WsHandler[R]
, state: Ref[Protocol]
)(
  chunk: Chunk[Byte]
): ZIO[Has[R], Throwable, Unit] =
  for {
    data <- state.get
    data <-
      data match
        case p: Http => processHttp(ch, hth, wsh)(p, chunk)
        case p: WsProto => processWs(ch, wsh)(p, chunk)
    _ <- state.set(data)
  } yield unit

def bind[R](
  addr: SocketAddress
, hth: HttpHandler[R]
, wsh: WsHandler[R]
): ZIO[Has[R], Throwable, Unit] =
  for {
    r <- ZIO.environment[Has[R]]
    _ <- tcp.bind(addr, 1, ch =>
      for {
        state <- Ref.make[Protocol](Protocol.http)
        h = (x: Request) => hth(x)
        w = (x: Msg) => wsh(x)
      } yield httpProtocol(ch, h, w, state)(_).provide(r)
    )
  } yield unit