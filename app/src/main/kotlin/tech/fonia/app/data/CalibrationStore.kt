package tech.fonia.app.data

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.fonia.app.judge.JudgeContext
import tech.fonia.app.judge.Talker
import tech.fonia.model.SoundId

/**
 * The current [CalibrationBook], rebuilt from the attempt rows at start and after every adult thumb. The lesson reads
 * [context] for each take (the judge's talker group, calibration tier, anchors, child F0 and offsets).
 */
class CalibrationStore(private val log: AttemptLog, private val talker: () -> Talker) {
    private val lock = Mutex()

    @Volatile var book: CalibrationBook = CalibrationBook.Empty
        private set

    suspend fun refresh(): CalibrationBook = lock.withLock {
        runCatching { CalibrationBook.build(log.allAttempts(), childTalker = talker() == Talker.Child) }
            .onFailure { Log.w("FoniaCalibration", "rebuild failed", it) }
            .getOrDefault(book)
            .also { book = it }
    }

    fun context(sound: SoundId, adultSeen: Boolean): JudgeContext {
        val t = talker()
        return JudgeContext(talker = t, adultPresent = adultSeen || t != Talker.Child, calibration = book.forSound(sound))
    }

    fun clear() {
        book = CalibrationBook.Empty
    }
}
