package ru.n08i40k.streaks.database

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import org.telegram.messenger.ApplicationLoader
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.LaunchActivity
import ru.n08i40k.streaks.Plugin
import ru.n08i40k.streaks.i18n.Strings
import ru.n08i40k.streaks.util.BulletinHelper
import ru.n08i40k.streaks.util.Logger
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class DatabaseMaintenance(private val backupManager: DatabaseBackupManager) {
    companion object {
        private const val PICKER_REQUEST_CODE = 0x5A7B
        private const val DATABASE_NAME = "tg-streaks"
        private const val IMPORT_FILE_NAME = "tg-streaks-restore-import.sqlite3"

        private fun databaseFiles(): List<File> {
            val base = ApplicationLoader.applicationContext.getDatabasePath(DATABASE_NAME)

            return listOf(
                base,
                File("${base.path}-wal"),
                File("${base.path}-shm"),
                File("${base.path}-journal"),
            )
        }
    }

    private val pickerPending = AtomicBoolean(false)

    @UiThread
    fun showRestoreDialog() {
        val fragment = LaunchActivity.getSafeLastFragment() ?: return
        val backups = backupManager.listBackups()

        val names = backups.map { it.name } + Strings.dialog_backup_restore_browse()

        fragment.showDialog(
            AlertDialog.Builder(fragment.context)
                .setTitle(Strings.dialog_backup_restore_title())
                .setItems(names.toTypedArray<CharSequence>()) { _, which ->
                    val backup = backups.getOrNull(which)

                    if (backup != null)
                        restore(backup, backup.name)
                    else
                        openFilePicker()
                }
                .create()
        )
    }

    @UiThread
    private fun openFilePicker() {
        val fragment = LaunchActivity.getSafeLastFragment() ?: return

        val intent = Intent(Intent.ACTION_GET_CONTENT)
            .setType("*/*")
            .addCategory(Intent.CATEGORY_OPENABLE)

        pickerPending.set(true)

        try {
            fragment.startActivityForResult(intent, PICKER_REQUEST_CODE)
        } catch (e: Exception) {
            pickerPending.set(false)
            Logger.info("Failed to open backup file picker: ${e.message}")
            BulletinHelper.error(Strings.status_error_backup_apply_failed(e.message.orEmpty()))
        }
    }

    @AnyThread
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != PICKER_REQUEST_CODE || !pickerPending.compareAndSet(true, false))
            return

        if (resultCode != Activity.RESULT_OK)
            return

        val uri = data?.data ?: return

        thread(name = "tg-streaks-backup-import") {
            try {
                val (file, name) = copyToCache(uri)
                restore(file, name)
            } catch (e: Exception) {
                Logger.info("Failed to read backup from uri: ${e.message}")
                BulletinHelper.error(Strings.status_error_backup_apply_failed(e.message.orEmpty()))
            }
        }
    }

    private fun copyToCache(uri: Uri): Pair<File, String> {
        val context = ApplicationLoader.applicationContext
        val resolver = context.contentResolver

        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull() ?: "backup"

        val target = File(context.cacheDir, IMPORT_FILE_NAME)

        val input = resolver.openInputStream(uri)
            ?: throw IllegalStateException("Content provider returned no stream")

        input.use { stream ->
            target.outputStream().use { stream.copyTo(it) }
        }

        return target to name
    }

    @AnyThread
    private fun restore(backup: File, name: String) {
        Plugin.reload("restore database backup $name") {
            if (!backup.isFile) {
                BulletinHelper.error(Strings.status_error_backup_not_found())
                return@reload
            }

            try {
                val files = databaseFiles()
                files.forEach { it.delete() }

                files.first().parentFile?.mkdirs()
                backup.copyTo(files.first(), overwrite = true)

                BulletinHelper.success(Strings.status_success_backup_imported(name))
            } catch (e: Exception) {
                BulletinHelper.error(Strings.status_error_backup_apply_failed(e.message.orEmpty()))
            }
        }
    }

    @AnyThread
    fun reset() {
        Plugin.reload("reset database") {
            try {
                databaseFiles().forEach { it.delete() }
                BulletinHelper.success(Strings.status_success_database_reset_started())
            } catch (e: Exception) {
                BulletinHelper.error(Strings.status_error_database_delete_failed(e.message.orEmpty()))
            }
        }
    }
}
