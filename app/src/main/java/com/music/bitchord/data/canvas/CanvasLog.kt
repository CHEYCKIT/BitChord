package com.music.bitchord.data.canvas

import com.music.bitchord.data.TrackLog

/**
 * [TrackLog] for the Spotify canvas chain, with the tag kept in the line.
 *
 * Canvas is otherwise left out of Copy Log (see [TrackLog]), and its lines
 * went through `DebugLog`, which a release build drops entirely — so when the
 * Spotify canvas stopped showing on a release build there was no way to tell
 * which step of the chain (token harvest, client token, search, canvas query)
 * had given up. This puts that chain in Copy Log, prefixed with the tag since
 * [TrackLog] keeps only the message.
 */
internal object CanvasLog {
    fun d(tag: String, message: String) = TrackLog.d(tag, "$tag: $message")
    fun w(tag: String, message: String) = TrackLog.w(tag, "$tag: $message")
}
