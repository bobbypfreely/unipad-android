package com.kimjisub.launchpad.tool

import android.annotation.SuppressLint
import android.media.MediaPlayer
import com.kimjisub.launchpad.manager.FileManager
import com.kimjisub.launchpad.unipack.UniPackFolder
import com.kimjisub.launchpad.unipack.struct.AutoPlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

class UniPackAutoMapper(
	private val unipack: UniPackFolder,
	private val listener: Listener,
) {
	interface Listener {
		fun onStart()
		fun onGetWorkSize(size: Int)
		fun onProgress(progress: Int)
		fun onDone()
		fun onException(throwable: Throwable)
	}

	init {
		CoroutineScope(Dispatchers.IO).launch {
			try {
				withContext(Dispatchers.Main) { listener.onStart() }

				val filtered = ArrayList<AutoPlay.Element>()
				for (e in unipack.autoPlayTable!!.elements) {
					when (e) {
						is AutoPlay.Element.On -> filtered.add(e)
						is AutoPlay.Element.Off -> {}
						is AutoPlay.Element.Chain -> filtered.add(e)
						is AutoPlay.Element.Delay -> filtered.add(e)
					}
				}

				// Merge consecutive delays
				val merged = ArrayList<AutoPlay.Element>()
				var prevDelay: AutoPlay.Element.Delay? = AutoPlay.Element.Delay(0)
				for (e in filtered) {
					when (e) {
						is AutoPlay.Element.On -> {
							if (prevDelay != null) {
								merged.add(prevDelay)
								prevDelay = null
							}
							merged.add(e)
						}
						is AutoPlay.Element.Chain -> merged.add(e)
						is AutoPlay.Element.Delay -> {
							if (prevDelay != null) prevDelay.delay += e.delay
							else prevDelay = e
						}
						else -> {}
					}
				}

				withContext(Dispatchers.Main) { listener.onGetWorkSize(merged.size) }

				// Replace delays with actual sound duration
				val result = ArrayList<AutoPlay.Element>()
				var nextDuration = 1000
				val mplayer = MediaPlayer()
				for ((i, e) in merged.withIndex()) {
					try {
						when (e) {
							is AutoPlay.Element.On -> {
								val sounds = unipack.soundTable!![e.currChain][e.x][e.y]!!
								val num = e.num % sounds.size
								nextDuration = FileManager.wavDuration(mplayer, sounds.elementAt(num).file.path)
								result.add(e)
							}
							is AutoPlay.Element.Chain -> result.add(e)
							is AutoPlay.Element.Delay -> {
								e.delay = nextDuration + AUTOMAPPING_DELAY_OFFSET_MS
								result.add(e)
							}
							else -> {}
						}
					} catch (ee: Exception) {
						ee.printStackTrace()
					}
					withContext(Dispatchers.Main) { listener.onProgress(i) }
				}
				mplayer.release()

				// Build autoPlay file content
				val sb = StringBuilder()
				for (e in result) {
					when (e) {
						is AutoPlay.Element.On ->
							sb.append("t ").append(e.x + 1).append(" ").append(e.y + 1).append("\n")
						is AutoPlay.Element.Chain ->
							sb.append("c ").append(e.c + 1).append("\n")
						is AutoPlay.Element.Delay ->
							sb.append("d ").append(e.delay).append("\n")
						else -> {}
					}
				}

				// Backup existing autoPlay file and write new one
				try {
					val autoPlayFile = unipack.autoPlayFile ?: File(unipack.rootFolder, "autoPlay")
					val existingFile = File(unipack.rootFolder, "autoPlay")
					if (existingFile.exists()) {
						@SuppressLint("SimpleDateFormat")
						val backupName = "autoPlay_" + SimpleDateFormat("yyyy_MM_dd-HH_mm_ss").format(Date())
						existingFile.renameTo(File(unipack.rootFolder, backupName))
					}
					BufferedWriter(OutputStreamWriter(FileOutputStream(autoPlayFile))).use { writer ->
						writer.write(sb.toString())
					}
				} catch (e: IOException) {
					e.printStackTrace()
				}

				withContext(Dispatchers.Main) { listener.onDone() }

			} catch (e: Throwable) {
				e.printStackTrace()
				withContext(Dispatchers.Main) { listener.onException(e) }
			}
		}
	}

	companion object {
		private const val AUTOMAPPING_DELAY_OFFSET_MS = 0
	}
}
