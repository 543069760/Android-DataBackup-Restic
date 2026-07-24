package com.xayah.libnative

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RusticInstrumentedTest {
    private lateinit var workspace: TestWorkspace

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        workspace = TestWorkspace.createIn(context.filesDir)
        logStep("Created test workspace: ${workspace.root.absolutePath}")
    }

    @After
    fun tearDown() {
        if (::workspace.isInitialized) {
            logStep("Deleting test workspace: ${workspace.root.absolutePath}")
            workspace.delete()
        }
    }

    @Test
    fun repositoryLifecycleCreatesAndRestoresSnapshot() {
        logStep("Writing source file: $SOURCE_FILE")
        workspace.writeSourceFile(SOURCE_FILE, SOURCE_CONTENT)

        logStep("Initializing repository: ${workspace.repositoryPath}")
        Rustic.initRepository(workspace.repositoryPath, PASSWORD)

        val snapshotId = createSnapshot()

        logStep("Restoring snapshot $snapshotId to ${workspace.restorePath}")
        Rustic.restoreSnapshot(workspace.repositoryPath, PASSWORD, snapshotId, workspace.restorePath)

        logStep("Checking repository integrity: ${workspace.repositoryPath}")
        Rustic.checkRepository(workspace.repositoryPath, PASSWORD)

        val restoredFile = workspace.requireRestoredFile(SOURCE_FILE)
        logStep("Verifying restored file content: ${restoredFile.absolutePath}")
        assertEquals(SOURCE_CONTENT, restoredFile.readText())
    }

    @Test
    fun repositoryLifecycleWithOpenDalFsOptions() {
        logStep("Writing source file: $SOURCE_FILE")
        workspace.writeSourceFile(SOURCE_FILE, SOURCE_CONTENT)

        // opendal fs 后端：location 用 "opendal:fs"，仓库数据落在 options["root"] 指向的目录
        val backendRoot = workspace.root.resolve("opendal-fs-root").apply { mkdirs() }
        val options = mapOf("root" to backendRoot.absolutePath)
        val location = "opendal:fs"

        logStep("Initializing opendal:fs repository, root=${backendRoot.absolutePath}")
        Rustic.initRepository(location, PASSWORD, options)

        assertTrue(
            "Repository should exist after init via opendal:fs",
            Rustic.repositoryExists(location, options),
        )

        logStep("Creating snapshot via opendal:fs")
        val snapshotId = Rustic.createSnapshot(
            repositoryPath = location,
            password = PASSWORD,
            sourcePaths = listOf(workspace.sourcePath),
            tags = listOf(SNAPSHOT_TAG),
            options = options,
        )
        assertTrue("Snapshot ID should not be blank", snapshotId.isNotBlank())

        logStep("Restoring snapshot $snapshotId to ${workspace.restorePath}")
        Rustic.restoreSnapshot(location, PASSWORD, snapshotId, workspace.restorePath, options)

        logStep("Checking repository integrity via opendal:fs")
        Rustic.checkRepository(location, PASSWORD, options)

        // 证据 1：仓库文件确实落进了 options["root"]，说明 map 透传到了 opendal 后端
        assertTrue(
            "opendal fs root should contain repository files",
            backendRoot.walkTopDown().any(File::isFile),
        )

        // 证据 2：内容能端到端还原
        val restoredFile = workspace.requireRestoredFile(SOURCE_FILE)
        assertEquals(SOURCE_CONTENT, restoredFile.readText())
    }

    @Test
    fun getVersionReturnsNonBlankVersion() {
        logStep("Querying rustic version")
        val version = Rustic.getVersion()
        logStep("Rustic version: $version")
        assertTrue("Version should not be blank", version.isNotBlank())
    }

    @Test
    fun forgetSnapshotRemovesSnapshot() {
        logStep("Writing source file: $SOURCE_FILE")
        workspace.writeSourceFile(SOURCE_FILE, SOURCE_CONTENT)

        logStep("Initializing repository: ${workspace.repositoryPath}")
        Rustic.initRepository(workspace.repositoryPath, PASSWORD)

        val snapshotId = createSnapshot()

        logStep("Forgetting snapshot: $snapshotId")
        Rustic.forgetSnapshot(workspace.repositoryPath, PASSWORD, snapshotId)

        logStep("Checking repository integrity after forget")
        Rustic.checkRepository(workspace.repositoryPath, PASSWORD)
    }

    @Test
    fun pruneRepositoryKeepsRepositoryUsable() {
        logStep("Writing source file: $SOURCE_FILE")
        workspace.writeSourceFile(SOURCE_FILE, SOURCE_CONTENT)

        logStep("Initializing repository: ${workspace.repositoryPath}")
        Rustic.initRepository(workspace.repositoryPath, PASSWORD)

        val snapshotId = createSnapshot()

        logStep("Forgetting snapshot before prune: $snapshotId")
        Rustic.forgetSnapshot(workspace.repositoryPath, PASSWORD, snapshotId)

        logStep("Pruning repository with maxUnused=10%")
        Rustic.pruneRepository(workspace.repositoryPath, PASSWORD, "10%")

        logStep("Checking repository integrity after prune")
        Rustic.checkRepository(workspace.repositoryPath, PASSWORD)
    }

    @Test
    fun listSnapshotsDbWritesQueryableDb() {
        logStep("Writing source file: $SOURCE_FILE")
        workspace.writeSourceFile(SOURCE_FILE, SOURCE_CONTENT)

        logStep("Initializing repository: ${workspace.repositoryPath}")
        Rustic.initRepository(workspace.repositoryPath, PASSWORD)

        val snapshotId = createSnapshot()

        val dbPath = workspace.root.resolve("snapshots.db").absolutePath
        logStep("Writing snapshots db to: $dbPath")
        Rustic.listSnapshotsDb(workspace.repositoryPath, PASSWORD, dbPath)

        assertTrue("Snapshots db should exist", File(dbPath).exists())

        val db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val cursor = db.rawQuery(
                "SELECT id, tags_flat FROM v_snapshots_full WHERE tags_flat IS NOT NULL",
                null,
            )
            try {
                assertTrue("Expected at least one snapshot row", cursor.count >= 1)

                var matchedTag = false
                var matchedId = false
                val idIndex = cursor.getColumnIndexOrThrow("id")
                val tagsIndex = cursor.getColumnIndexOrThrow("tags_flat")
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIndex)
                    if (id == snapshotId) {
                        matchedId = true
                    }
                    val tagsFlat = cursor.getString(tagsIndex) ?: ""
                    val tags = tagsFlat.split(31.toChar()).filter { it.isNotEmpty() }
                    if (tags.contains(SNAPSHOT_TAG)) {
                        matchedTag = true
                    }
                }

                assertTrue("Expected a row with snapshot id $snapshotId", matchedId)
                assertTrue("Expected a row tagged $SNAPSHOT_TAG", matchedTag)
            } finally {
                cursor.close()
            }
        } finally {
            db.close()
        }
    }

    private fun createSnapshot(): String {
        logStep("Creating snapshot from source: ${workspace.sourcePath}")
        val snapshotId = Rustic.createSnapshot(
            repositoryPath = workspace.repositoryPath,
            password = PASSWORD,
            sourcePaths = listOf(workspace.sourcePath),
            tags = listOf(SNAPSHOT_TAG),
        )

        assertTrue("Snapshot ID should not be blank", snapshotId.isNotBlank())
        logStep("Created snapshot: $snapshotId")
        return snapshotId
    }

    private fun logStep(message: String) {
        Log.i(TAG, message)
    }

    private class TestWorkspace private constructor(
        val root: File,
        val repository: File,
        val source: File,
        val restore: File,
    ) {
        val repositoryPath: String = repository.absolutePath
        val sourcePath: String = source.absolutePath
        val restorePath: String = restore.absolutePath

        fun writeSourceFile(path: String, content: String) {
            source.resolve(path).apply {
                parentFile?.mkdirs()
                writeText(content)
            }
        }

        fun requireRestoredFile(path: String): File {
            val matches = restore.walkTopDown()
                .filter(File::isFile)
                .filter { it.invariantSeparatorsPath.endsWith("/$path") }
                .toList()

            assertEquals("Expected exactly one restored file ending with $path", 1, matches.size)
            return matches.single()
        }

        fun delete() {
            root.deleteRecursively()
        }

        companion object {
            fun createIn(parent: File): TestWorkspace {
                val root = parent.resolve("rustic-instrumented-${System.nanoTime()}").apply {
                    deleteRecursively()
                    mkdirs()
                }

                return TestWorkspace(
                    root = root,
                    repository = root.resolve("repo"),
                    source = root.resolve("source"),
                    restore = root.resolve("restore"),
                )
            }
        }
    }

    private companion object {
        init {
            System.loadLibrary("rustic")
            Rustic.initLogger()
        }

        const val TAG = "RusticInstrumentedTest"
        const val PASSWORD = "instrumented-password"
        const val SNAPSHOT_TAG = "instrumented"
        const val SOURCE_FILE = "nested/note.txt"
        const val SOURCE_CONTENT = "Hello from Rust"
    }
}