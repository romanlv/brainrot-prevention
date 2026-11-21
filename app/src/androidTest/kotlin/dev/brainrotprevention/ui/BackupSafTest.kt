package dev.brainrotprevention.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.brainrotprevention.model.BackupCodec
import dev.brainrotprevention.testing.Milestone
import dev.brainrotprevention.ui.nav.Routes
import java.io.File
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** §4.3 backup through the Storage Access Framework: export uses ACTION_CREATE_DOCUMENT, import ACTION_OPEN_DOCUMENT. */
@RunWith(AndroidJUnit4::class)
@Milestone("M9")
class BackupSafTest {
    @get:Rule val rule = createComposeRule()
    private val env = TestEnv()
    private lateinit var file: File

    @Before fun setUp() {
        Intents.init()
        file = File(env.dir, "backup.json")
    }

    @After fun tearDown() {
        Intents.release()
        env.close()
    }

    @Test
    fun exportWritesTheBackupToTheChosenDocument() {
        env.seed(TestEnv.workday("Focus"))
        env.seed(TestEnv.workday("Evening", apps = listOf("com.example.b")))
        intending(hasAction(Intent.ACTION_CREATE_DOCUMENT))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))))
        rule.show(env, Routes.SETTINGS)
        rule.scrollTap("backup_export")
        rule.waitUntil(10_000) { file.length() > 0 }
        intended(allOf(hasAction(Intent.ACTION_CREATE_DOCUMENT), hasExtra(Intent.EXTRA_TITLE, "brainrot-prevention-2026-11-23.json")))
        val backup = BackupCodec.decode(file.readText())
        assertEquals(listOf("Focus", "Evening"), backup.schedules.map { it.name })
        rule.waitForTag("notice_dialog")
    }

    @Test
    fun importReplacesSchedulesAfterConfirmation() {
        env.seed(TestEnv.workday("Old"))
        val text = runBlocking {
            val other = TestEnv()
            try {
                other.seed(TestEnv.workday("Imported"))
                other.graph.backup.exportText()
            } finally {
                other.close()
            }
        }
        file.writeText(text)
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))))
        rule.show(env, Routes.SETTINGS)
        rule.scrollTap("backup_import")
        rule.tap("import_confirm_confirm")
        rule.waitUntil(10_000) { env.schedules().map { it.name } == listOf("Imported") }
    }

    @Test
    fun importIsRefusedWhileStrictModeIsOn() {
        file.writeText(runBlocking { env.graph.backup.exportText() })
        env.strictOn()
        intending(hasAction(Intent.ACTION_OPEN_DOCUMENT))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))))
        rule.show(env, Routes.SETTINGS)
        rule.scrollTap("backup_import")
        rule.tap("import_confirm_confirm")
        rule.waitForTag("locked_by_strict")
    }
}
