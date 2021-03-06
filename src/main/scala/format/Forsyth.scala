package chess
package format

/**
 * Transform a game to standard Forsyth Edwards Notation
 * http://en.wikipedia.org/wiki/Forsyth%E2%80%93Edwards_Notation
 */
object Forsyth {

  val initial = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  def <<(rawSource: String): Option[Situation] = read(rawSource) { source =>
    makeBoard(source) map { board =>
      val fixedSource = fixCastles(source) | source
      val splitted = fixedSource split ' '
      val colorOption = splitted lift 1 flatMap (_ lift 0) flatMap Color.apply
      val situation = colorOption match {
        case Some(color)             => Situation(board, color)
        case _ if board.check(Black) => Situation(board, Black) // user in check will move first
        case _                       => Situation(board, White)
      }
      splitted.lift(2).fold(situation) { castles =>
        val fifthRank = if (situation.color == White) 5 else 4
        val sixthRank = if (situation.color == White) 6 else 3
        val seventhRank = if (situation.color == White) 7 else 2
        val lastMove = splitted lift 3 flatMap Pos.posAt match {
          case Some(pos) if pos.y == sixthRank
            && Pos.posAt(pos.x, fifthRank).flatMap(situation.board.apply).contains(Piece(!situation.color, Pawn))
            && Pos.posAt(pos.x, sixthRank).flatMap(situation.board.apply).isEmpty
            && Pos.posAt(pos.x, seventhRank).flatMap(situation.board.apply).isEmpty =>
            Some(s"${pos.file}${seventhRank}${pos.file}${fifthRank}")
          case _ =>
            None
        }
        situation withHistory History.make(lastMove, castles)
      }
    }
  }

  // only cares about pieces positions on the board (first part of FEN string)
  def makeBoard(rawSource: String): Option[Board] = read(rawSource) { source =>
    makePieces(source.takeWhile(' '!=).replace("/", "").toList, Pos.A8) map { pieces =>
      Board(pieces, variant = chess.variant.Variant.default)
    }
  }

  private def makePieces(chars: List[Char], pos: Pos): Option[List[(Pos, Piece)]] = chars match {
    case Nil => Some(Nil)
    case c :: rest => c match {
      case n if n.toInt < 58 =>
        makePieces(rest, tore(pos, n.toInt - 48) getOrElse pos)
      case n => Role forsyth n.toLower map { role =>
        (pos, Piece(Color(n.isUpper), role)) :: {
          tore(pos, 1) flatMap { makePieces(rest, _) } getOrElse Nil
        }
      }
    }
  }

  case class SituationPlus(situation: Situation, fullMoveNumber: Int) {

    def turns = fullMoveNumber * 2 - (if (situation.color.white) 2 else 1)
  }

  def <<<(rawSource: String): Option[SituationPlus] = read(rawSource) { source =>
    <<(source) map { situation =>
      val fullMoveNumber = source split ' ' lift 5 flatMap parseIntOption map (_ max 1 min 500)
      SituationPlus(situation, fullMoveNumber | 1)
    }
  }

  def >>(situation: Situation): String = >>(SituationPlus(situation, 0))

  def >>(parsed: SituationPlus): String = parsed match {
    case SituationPlus(Situation(board, color), _) => >>(Game(board, color, turns = parsed.turns))
  }

  def >>(game: Game): String = List(
    exportBoard(game.board),
    game.player.letter,
    game.board.history.castles.toString,
    ((for {
      lastMove ← game.board.history.lastMove
      piece ← game board lastMove.dest
      if piece is Pawn
      pos ← if (lastMove.orig.y == 2 && lastMove.dest.y == 4) lastMove.dest.down
      else if (lastMove.orig.y == 7 && lastMove.dest.y == 5) lastMove.dest.up
      else None
    } yield pos.toString) getOrElse "-"),
    game.halfMoveClock,
    game.fullMoveNumber
  ) mkString " "

  def tore(pos: Pos, n: Int): Option[Pos] = Pos.posAt(
    ((pos.x + n - 1) % 8 + 1),
    (pos.y - (pos.x + n - 1) / 8)
  )

  def exportBoard(board: Board): String = {
    val fen = new scala.collection.mutable.StringBuilder(70)
    var empty = 0
    for (y ← 8 to 1 by -1) {
      empty = 0
      for (x <- 1 to 8) {
        board(x, y) match {
          case None => empty = empty + 1
          case Some(piece) =>
            if (empty == 0) fen append piece.forsyth.toString
            else {
              fen append (empty.toString + piece.forsyth)
              empty = 0
            }
        }
      }
      if (empty > 0) fen append empty
      if (y > 1) fen append "/"
    }
    fen.toString
  }

  def getFullMove(rawSource: String): Option[Int] = read(rawSource) { fen =>
    fen.split(' ').lift(5) flatMap parseIntOption
  }

  def getColor(rawSource: String): Option[Color] = read(rawSource) { fen =>
    fen.split(' ').lift(1) flatMap (_.headOption) flatMap Color.apply
  }

  def getPly(rawSource: String): Option[Int] = read(rawSource) { fen =>
    getFullMove(fen) map { fullMove =>
      fullMove * 2 - (if (getColor(fen).exists(_.white)) 2 else 1)
    }
  }

  def fixCastles(rawSource: String): Option[String] = read(rawSource) { fen =>
    fen.split(' ').toList match {
      case boardStr :: color :: castlesStr :: rest => makeBoard(boardStr) map { board =>
        val c1 = Castles(castlesStr)
        val wkPos = board.kingPosOf(White)
        val bkPos = board.kingPosOf(Black)
        val wkReady = wkPos.fold(false)(_.y == 1)
        val bkReady = bkPos.fold(false)(_.y == 8)
        def rookReady(color: Color, kPos: Option[Pos], left: Boolean) = kPos.fold(false) { kp =>
          board actorsOf color exists { a =>
            a.piece.role == Rook && a.pos.y == kp.y && (left ^ (a.pos.x > kp.x))
          }
        }
        val c2 = Castles(
          whiteKingSide = c1.whiteKingSide && wkReady && rookReady(White, wkPos, false),
          whiteQueenSide = c1.whiteQueenSide && wkReady && rookReady(White, wkPos, true),
          blackKingSide = c1.blackKingSide && bkReady && rookReady(Black, bkPos, false),
          blackQueenSide = c1.blackQueenSide && bkReady && rookReady(Black, bkPos, true))
        s"$boardStr $color $c2 ${rest.mkString(" ")}"
      }
      case _ => None
    }
  }

  private def read[A](source: String)(f: String => A): A = f(source.replace("_", " ").trim)
}
