package com.craftworks.music.managers

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import java.io.File

object DocumentUriUtils {
    private const val TAG = "DOC_URI_UTILS"

    fun getPathFromTreeUri(context: Context, uri: Uri): String? {
        try {
            if (DocumentsContract.isTreeUri(uri)) {
                val documentId = DocumentsContract.getTreeDocumentId(uri)
                val split = documentId.split(":")
                if (split.size >= 2) {
                    val type = split[0]
                    val relativePath = split[1]
                    if ("primary".equals(type, ignoreCase = true)) {
                        val base = Environment.getExternalStorageDirectory().absolutePath
                        return if (relativePath.isNotEmpty()) "$base/$relativePath" else base
                    } else {
                        val file = File("/storage/$type/$relativePath")
                        if (file.exists()) return file.absolutePath
                    }
                }
            } else if ("file".equals(uri.scheme, ignoreCase = true)) {
                return uri.path
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve path from URI: $uri", e)
        }
        return uri.path
    }
}
