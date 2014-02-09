package lila.puzzle

import org.goochjs.glicko2._
import org.joda.time.DateTime
import reactivemongo.bson.{ BSONDocument, BSONInteger }

import lila.db.Types.Coll
import lila.rating.{ Glicko, Perf }
import lila.user.{ User, UserRepo }

private[puzzle] final class Finisher(
    api: PuzzleApi,
    puzzleColl: Coll) {

  def apply(puzzle: Puzzle, user: User, data: DataForm.AttemptData): Fu[Attempt] =
    api.attempt.find(puzzle.id, user.id) flatMap {
      case Some(a) ⇒ fuccess(a)
      case None ⇒
        val userRating = mkRating(user.perfs.puzzle)
        val puzzleRating = mkRating(puzzle.perf)
        updateRatings(userRating, puzzleRating, data.isWin.fold(Glicko.Result.Win, Glicko.Result.Loss))
        val userPerf = mkPerf(userRating)
        val puzzlePerf = mkPerf(puzzleRating)
        val a = new Attempt(
          id = Attempt.makeId(puzzle.id, user.id),
          puzzleId = puzzle.id,
          userId = user.id,
          date = DateTime.now,
          win = data.isWin,
          time = data.time,
          vote = none,
          puzzleRating = puzzle.perf.intRating,
          puzzleRatingDiff = puzzlePerf.intRating - puzzle.perf.intRating,
          userRating = user.perfs.puzzle.intRating,
          userRatingDiff = userPerf.intRating - user.perfs.puzzle.intRating)
        (api.attempt add a) >> (api.attempt times puzzle.id) flatMap { times ⇒
          puzzleColl.update(
            BSONDocument("_id" -> puzzle.id),
            BSONDocument("$inc" -> BSONDocument(
              Puzzle.BSONFields.attempts -> BSONInteger(1),
              Puzzle.BSONFields.wins -> BSONInteger(data.isWin ? 1 | 0)
            )) ++ BSONDocument("$set" -> BSONDocument(
              Puzzle.BSONFields.time -> BSONInteger(times.sum / (puzzle.attempts + 1)),
              Puzzle.BSONFields.perf -> Perf.perfBSONHandler.write(puzzlePerf)
            )) ++ BSONDocument("$addToSet" -> BSONDocument(
              Puzzle.BSONFields.users -> user.id
            ))) zip UserRepo.setPerf(user.id, "puzzle", userPerf)
        } inject a
    }

  def anon(puzzle: Puzzle, data: DataForm.AttemptData): Funit = puzzleColl.update(
    BSONDocument("_id" -> puzzle.id),
    BSONDocument("$inc" -> BSONDocument(
      Puzzle.BSONFields.attempts -> BSONInteger(1),
      Puzzle.BSONFields.wins -> BSONInteger(data.isWin ? 1 | 0)
    ))).void

  private val VOLATILITY = Glicko.default.volatility
  private val TAU = 0.75d
  private val system = new RatingCalculator(VOLATILITY, TAU)

  private def mkRating(perf: Perf) = new Rating(
    perf.glicko.rating, perf.glicko.deviation, perf.glicko.volatility, perf.nb)

  private def mkPerf(rating: Rating): Perf = Perf(
    Glicko(rating.getRating, rating.getRatingDeviation, rating.getVolatility),
    nb = rating.getNumberOfResults,
    latest = DateTime.now.some)

  private def updateRatings(u1: Rating, u2: Rating, result: Glicko.Result) {
    val results = new RatingPeriodResults()
    result match {
      case Glicko.Result.Draw ⇒ results.addDraw(u1, u2)
      case Glicko.Result.Win  ⇒ results.addResult(u1, u2)
      case Glicko.Result.Loss ⇒ results.addResult(u2, u1)
    }
    try {
      system.updateRatings(results)
    }
    catch {
      case e: Exception ⇒ play.api.Logger("Puzzle finisher").error(e.getMessage)
    }
  }
}