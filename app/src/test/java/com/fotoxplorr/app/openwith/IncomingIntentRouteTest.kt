package com.fotoxplorr.app.openwith

import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingIntentRouteTest {

    private val view = "android.intent.action.VIEW"
    private val edit = "android.intent.action.EDIT"
    private val send = "android.intent.action.SEND"
    private val sendMultiple = "android.intent.action.SEND_MULTIPLE"

    @Test
    fun `viewing an image opens the viewer on a still photo`() {
        val route = classifyIncomingIntent(view, "content://media/1", "image/jpeg")
        assertEquals(IncomingIntentRoute.ViewMedia(listOf("content://media/1"), isVideo = false), route)
    }

    @Test
    fun `viewing a video opens the viewer flagged as video`() {
        val route = classifyIncomingIntent(view, "content://media/2", "video/mp4")
        assertEquals(IncomingIntentRoute.ViewMedia(listOf("content://media/2"), isVideo = true), route)
    }

    @Test
    fun `viewing audio opens the audio player, not the viewer`() {
        val route = classifyIncomingIntent(view, "content://media/3", "audio/mpeg")
        assertEquals(IncomingIntentRoute.ViewAudio("content://media/3"), route)
    }

    @Test
    fun `editing an image routes straight to the editor`() {
        val route = classifyIncomingIntent(edit, "content://media/4", "image/png")
        assertEquals(IncomingIntentRoute.EditImage("content://media/4"), route)
    }

    @Test
    fun `editing a video is not offered -- the editor is a photo tool`() {
        val route = classifyIncomingIntent(edit, "content://media/5", "video/mp4")
        assertEquals(IncomingIntentRoute.Unhandled, route)
    }

    @Test
    fun `a share with one image stream opens the viewer`() {
        val route = classifyIncomingIntent(send, dataUri = null, mimeType = "image/jpeg", streamUris = listOf("content://a"))
        assertEquals(IncomingIntentRoute.ViewMedia(listOf("content://a"), isVideo = false), route)
    }

    @Test
    fun `a multi-share of videos carries every stream`() {
        val route = classifyIncomingIntent(
            sendMultiple,
            dataUri = null,
            mimeType = "video/mp4",
            streamUris = listOf("content://a", "content://b"),
        )
        assertEquals(IncomingIntentRoute.ViewMedia(listOf("content://a", "content://b"), isVideo = true), route)
    }

    @Test
    fun `a shared audio file plays only the first stream`() {
        val route = classifyIncomingIntent(
            send,
            dataUri = null,
            mimeType = "audio/mpeg",
            streamUris = listOf("content://a", "content://b"),
        )
        assertEquals(IncomingIntentRoute.ViewAudio("content://a"), route)
    }

    @Test
    fun `a share with no stream and no data is unhandled`() {
        assertEquals(IncomingIntentRoute.Unhandled, classifyIncomingIntent(send, null, "image/jpeg"))
    }

    @Test
    fun `an unrecognised mime type is unhandled`() {
        assertEquals(IncomingIntentRoute.Unhandled, classifyIncomingIntent(view, "content://media/6", "application/pdf"))
    }

    @Test
    fun `no mime type at all is unhandled`() {
        assertEquals(IncomingIntentRoute.Unhandled, classifyIncomingIntent(view, "content://media/7", null))
    }

    @Test
    fun `a view with no data at all is unhandled`() {
        assertEquals(IncomingIntentRoute.Unhandled, classifyIncomingIntent(view, null, "image/jpeg"))
    }

    @Test
    fun `an unrelated action is unhandled`() {
        assertEquals(
            IncomingIntentRoute.Unhandled,
            classifyIncomingIntent("android.intent.action.MAIN", null, null),
        )
    }
}
