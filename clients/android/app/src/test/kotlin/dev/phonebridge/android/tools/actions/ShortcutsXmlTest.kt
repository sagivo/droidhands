package dev.phonebridge.android.tools.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

class ShortcutsXmlTest {
    private val strings = mapOf(
        "@string/compose_short" to "Compose",
        "@string/compose_long" to "Write a new email",
        "@2131755203" to "Inbox",
    )

    private fun parse(xml: String): ShortcutsDeclaration {
        val parser = KXmlParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))
        return ShortcutsXml.parse(parser) { strings[it] }
    }

    private fun doc(body: String) =
        """<?xml version="1.0" encoding="utf-8"?>
        <shortcuts xmlns:android="http://schemas.android.com/apk/res/android">$body</shortcuts>"""

    @Test
    fun readsShortcutsWithResolvedLabels() {
        val parsed = parse(
            doc(
                """
                <shortcut android:shortcutId="compose" android:enabled="true"
                    android:shortcutShortLabel="@string/compose_short" android:shortcutLongLabel="@string/compose_long">
                  <intent android:action="android.intent.action.VIEW" android:targetPackage="com.example.mail"
                      android:targetClass="com.example.mail.ComposeActivity" android:data="mailto:" />
                  <categories android:name="android.shortcut.conversation" />
                </shortcut>
                """,
            ),
        )
        val shortcut = parsed.shortcuts.single()
        assertEquals("compose", shortcut.id)
        assertTrue(shortcut.enabled)
        assertEquals("Compose", shortcut.shortLabel)
        assertEquals("Write a new email", shortcut.longLabel)
        assertEquals(
            DeclaredIntent(
                action = "android.intent.action.VIEW",
                targetPackage = "com.example.mail",
                targetClass = "com.example.mail.ComposeActivity",
                data = "mailto:",
            ),
            shortcut.intent,
        )
        assertTrue("shortcut categories are not intent categories", shortcut.intent!!.categories.isEmpty())
    }

    @Test
    fun lastIntentIsTheOneStartedAndExtrasAndCategoriesAreRead() {
        val parsed = parse(
            doc(
                """
                <shortcut android:shortcutId="inbox" android:shortcutShortLabel="@2131755203">
                  <intent android:action="android.intent.action.MAIN" android:targetPackage="com.example.mail"
                      android:targetClass="com.example.mail.Main" />
                  <intent android:action="android.intent.action.VIEW" android:targetPackage="com.example.mail"
                      android:targetClass="com.example.mail.Inbox">
                    <extra android:name="folder" android:value="inbox" />
                    <categories android:name="android.intent.category.DEFAULT" />
                  </intent>
                </shortcut>
                <shortcut android:shortcutId="old" android:enabled="false" android:shortcutShortLabel="Old">
                  <intent android:action="android.intent.action.VIEW" android:targetPackage="com.example.mail"
                      android:targetClass="com.example.mail.Old" />
                </shortcut>
                <shortcut android:shortcutShortLabel="No id">
                  <intent android:action="android.intent.action.VIEW" />
                </shortcut>
                """,
            ),
        )
        assertEquals(listOf("inbox", "old"), parsed.shortcuts.map { it.id })
        val inbox = parsed.shortcuts[0]
        assertEquals("Inbox", inbox.shortLabel)
        assertEquals("com.example.mail.Inbox", inbox.intent?.targetClass)
        assertEquals(mapOf("folder" to "inbox"), inbox.intent?.extras)
        assertEquals(listOf("android.intent.category.DEFAULT"), inbox.intent?.categories)
        assertFalse(parsed.shortcuts[1].enabled)
    }

    @Test
    fun readsCapabilitiesWithUrlTemplatesAndParameters() {
        val parsed = parse(
            doc(
                """
                <capability android:name="actions.intent.GET_THING">
                  <intent android:targetPackage="com.example.mail" android:targetClass="com.example.mail.Search">
                    <url-template android:value="mail://search{?q}" />
                    <parameter android:name="thing.name" android:key="q" android:required="true" />
                  </intent>
                  <intent android:targetClass="com.example.mail.Fallback" />
                </capability>
                <capability android:name="actions.intent.CREATE_MESSAGE">
                  <intent android:action="android.intent.action.SEND" android:targetPackage="com.example.mail"
                      android:targetClass="com.example.mail.Compose">
                    <parameter android:name="message.recipient.name" android:key="recipientName" />
                    <parameter android:name="message.text" android:key="messageText" />
                    <extra android:key="source" android:value="assistant" />
                  </intent>
                </capability>
                <capability>
                  <intent android:targetClass="x.Y" />
                </capability>
                """,
            ),
        )
        assertEquals(listOf("actions.intent.GET_THING", "actions.intent.CREATE_MESSAGE"), parsed.capabilities.map { it.name })
        val get = parsed.capabilities[0]
        assertEquals(2, get.intents.size)
        assertEquals("mail://search{?q}", get.intents[0].urlTemplate)
        assertEquals(listOf(DeclaredParameter("thing.name", "q", required = true)), get.intents[0].parameters)
        assertNull(get.intents[0].action)
        val message = parsed.capabilities[1].intents.single()
        assertEquals(listOf("recipientName", "messageText"), message.parameters.map { it.key })
        assertFalse(message.parameters.any { it.required })
        assertEquals(mapOf("source" to "assistant"), message.extras)
    }

    @Test
    fun skipsUnknownElementsAndUnresolvedReferences() {
        val parsed = parse(
            doc(
                """
                <shortcut android:shortcutId="a" android:shortcutShortLabel="@string/missing">
                  <capability-binding android:key="actions.intent.OPEN_APP_FEATURE">
                    <parameter-binding android:key="feature" android:value="@string/feature" />
                  </capability-binding>
                  <intent android:action="android.intent.action.VIEW" android:targetClass="com.example.A" />
                </shortcut>
                <slice><parameter android:key="ignored" /></slice>
                """,
            ),
        )
        val shortcut = parsed.shortcuts.single()
        assertNull(shortcut.shortLabel)
        assertEquals("com.example.A", shortcut.intent?.targetClass)
        assertTrue(parsed.capabilities.isEmpty())
    }

    @Test
    fun emptyDocument() {
        assertEquals(ShortcutsDeclaration(), parse(doc("")))
    }
}
