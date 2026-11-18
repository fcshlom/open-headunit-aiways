package com.andrerinas.openheadunit.aiways

import com.andrerinas.openheadunit.contract.NavigationUpdateIntent
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The turn-by-turn broadcast is sent with [NavigationUpdateIntent.BROADCAST_PERMISSION]. A
 * permission nobody declares can be held by no app, so the manifest has to declare exactly that
 * name, or the launcher never gets a single turn (as it was until 4.0.0-aiways.9).
 */
class NavigationPermissionTest {

    @Test
    fun `the manifest declares the permission the navigation broadcast is sent with`() {
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
            .map { File(it) }
            .first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest)
        val permissions = doc.getElementsByTagName("permission")
        val declared = (0 until permissions.length).map {
            permissions.item(it).attributes
                .getNamedItemNS("http://schemas.android.com/apk/res/android", "name").nodeValue
        }
        assertTrue(
            "declared: $declared",
            NavigationUpdateIntent.BROADCAST_PERMISSION in declared
        )
    }
}
