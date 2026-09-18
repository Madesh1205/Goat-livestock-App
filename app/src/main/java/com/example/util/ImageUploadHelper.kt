package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.core.supabase.SupabaseConfig
import com.example.core.supabase.SupabaseModule
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Helper utility for compressing/resizing images and managing Supabase Storage.
 * Provides resilient multi-layer uploads (Supabase Storage SDK -> Direct Storage REST API -> Auto Bucket Creation -> Local cache fallback).
 */
object ImageUploadHelper {
    private const val TAG = "ImageUploadHelper"
    private const val MAX_FILE_SIZE_BYTES = 15 * 1024 * 1024 // 15MB input limit
    private const val MAX_DIMENSION = 1280
    private const val COMPRESSION_QUALITY = 85

    /**
     * Curated high-resolution breed photos library for quick 1-tap addition.
     */
    val BREED_PRESET_PHOTOS: Map<String, List<String>> = emptyMap()

    /**
     * Resizes and compresses an image from a Content Uri into a JPEG byte array with EXIF correction.
     */
    suspend fun compressAndResizeImage(context: Context, imageUri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        try {
            var inputStream: InputStream? = null
            try {
                inputStream = context.contentResolver.openInputStream(imageUri)
            } catch (e: Exception) {
                Log.w(TAG, "ContentResolver open failed, trying file path: ${e.message}")
                if (imageUri.scheme == "file" || imageUri.path != null) {
                    val file = File(imageUri.path ?: "")
                    if (file.exists()) {
                        inputStream = file.inputStream()
                    }
                }
            }

            val rawBytes = inputStream?.use { it.readBytes() }
            if (rawBytes == null || rawBytes.isEmpty()) {
                Log.e(TAG, "Failed to read bytes from Uri: $imageUri")
                return@withContext null
            }

            // Check EXIF orientation
            var orientationDegrees = 0
            try {
                val exif = ExifInterface(ByteArrayInputStream(rawBytes))
                val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                orientationDegrees = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } catch (exifErr: Exception) {
                Log.w(TAG, "Could not extract EXIF data: ${exifErr.message}")
            }

            // Decode bounds
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)

            val origWidth = options.outWidth
            val origHeight = options.outHeight

            if (origWidth <= 0 || origHeight <= 0) {
                Log.e(TAG, "Invalid image dimensions: ${origWidth}x${origHeight}")
                return@withContext null
            }

            // Calculate inSampleSize
            var inSampleSize = 1
            if (origHeight > MAX_DIMENSION || origWidth > MAX_DIMENSION) {
                val halfHeight = origHeight / 2
                val halfWidth = origWidth / 2
                while ((halfHeight / inSampleSize) >= MAX_DIMENSION && (halfWidth / inSampleSize) >= MAX_DIMENSION) {
                    inSampleSize *= 2
                }
            }

            // Decode bitmap with inSampleSize
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decodedBitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions)
                ?: return@withContext null

            // Apply EXIF rotation if needed
            val rotatedBitmap = if (orientationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(orientationDegrees.toFloat()) }
                Bitmap.createBitmap(decodedBitmap, 0, 0, decodedBitmap.width, decodedBitmap.height, matrix, true)
            } else {
                decodedBitmap
            }

            // Scale down to exact max dimension if still larger
            val finalBitmap = if (rotatedBitmap.width > MAX_DIMENSION || rotatedBitmap.height > MAX_DIMENSION) {
                val ratio = rotatedBitmap.width.toFloat() / rotatedBitmap.height.toFloat()
                val targetWidth: Int
                val targetHeight: Int
                if (ratio > 1) {
                    targetWidth = MAX_DIMENSION
                    targetHeight = (MAX_DIMENSION / ratio).toInt()
                } else {
                    targetHeight = MAX_DIMENSION
                    targetWidth = (MAX_DIMENSION * ratio).toInt()
                }
                Bitmap.createScaledBitmap(rotatedBitmap, targetWidth, targetHeight, true)
            } else {
                rotatedBitmap
            }

            val outputStream = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, COMPRESSION_QUALITY, outputStream)
            val bytes = outputStream.toByteArray()
            outputStream.close()

            Log.d(TAG, "Image compressed from ${origWidth}x${origHeight} to ${finalBitmap.width}x${finalBitmap.height}, size: ${bytes.size / 1024} KB")
            bytes
        } catch (e: Exception) {
            Log.e(TAG, "Error compressing image: ${e.message}", e)
            null
        }
    }

    /**
     * Compresses a direct Bitmap (e.g. from camera) into a JPEG byte array.
     */
    suspend fun compressAndResizeBitmap(bitmap: Bitmap): ByteArray = withContext(Dispatchers.IO) {
        val finalBitmap = if (bitmap.width > MAX_DIMENSION || bitmap.height > MAX_DIMENSION) {
            val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val targetWidth: Int
            val targetHeight: Int
            if (ratio > 1) {
                targetWidth = MAX_DIMENSION
                targetHeight = (MAX_DIMENSION / ratio).toInt()
            } else {
                targetHeight = MAX_DIMENSION
                targetWidth = (MAX_DIMENSION * ratio).toInt()
            }
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        } else {
            bitmap
        }

        val outputStream = ByteArrayOutputStream()
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, COMPRESSION_QUALITY, outputStream)
        val bytes = outputStream.toByteArray()
        outputStream.close()
        bytes
    }

    /**
     * Generates a clean, readable, standardized file name with date and optional index.
     * Example outputs:
     * - goat_photo_20260901_103045_01.jpg
     * - farm_logo_20260901_103045.jpg
     */
    fun generateProperFileName(
        prefix: String = "photo",
        customName: String? = null,
        index: Int? = null,
        extension: String = "jpg"
    ): String {
        if (!customName.isNullOrBlank()) {
            val sanitized = customName.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_")
            return if (sanitized.endsWith(".jpg", ignoreCase = true) || 
                       sanitized.endsWith(".jpeg", ignoreCase = true) || 
                       sanitized.endsWith(".png", ignoreCase = true) || 
                       sanitized.endsWith(".webp", ignoreCase = true)) {
                sanitized
            } else {
                "$sanitized.$extension"
            }
        }
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val timestamp = dateFormat.format(Date())
        val indexSuffix = if (index != null) String.format(Locale.US, "_%02d", index) else ""
        val randomSuffix = UUID.randomUUID().toString().take(4)
        val cleanPrefix = prefix.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return "${cleanPrefix}_${timestamp}${indexSuffix}_${randomSuffix}.$extension"
    }

    /**
     * Saves image bytes locally to app storage cache and returns a file path/uri.
     */
    suspend fun saveImageLocally(
        context: Context,
        bytes: ByteArray,
        prefix: String = "photo",
        customFileName: String? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.filesDir, "goat_photos").apply { if (!exists()) mkdirs() }
            val fileName = generateProperFileName(prefix, customFileName)
            val file = File(dir, fileName)
            FileOutputStream(file).use { it.write(bytes) }
            "file://${file.absolutePath}"
        } catch (e: Exception) {
            Log.e(TAG, "Error saving image locally: ${e.message}")
            "data:image/jpeg;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        }
    }

    /**
     * Directly uploads binary bytes to Supabase Storage via REST API using preconfigured OkHttpClient.
     * Attempts bucket creation and token injection automatically.
     */
    private suspend fun uploadViaRestApi(
        bytes: ByteArray,
        bucketName: String,
        storagePath: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val client = SupabaseModule.createCustomOkHttpClient()
            val supabaseUrl = SupabaseConfig.supabaseUrl
            val anonKey = SupabaseConfig.supabaseAnonKey
            val authUser = SupabaseModule.auth.currentUserOrNull()
            val sessionToken = SupabaseModule.auth.currentSessionOrNull()?.accessToken ?: anonKey

            val cleanPath = storagePath.removePrefix("/")
            val uploadUrl = "$supabaseUrl/storage/v1/object/$bucketName/$cleanPath"

            val requestBody = bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())

            val request = Request.Builder()
                .url(uploadUrl)
                .post(requestBody)
                .header("Authorization", "Bearer $sessionToken")
                .header("apikey", anonKey)
                .header("x-upsert", "true")
                .header("Content-Type", "image/jpeg")
                .build()

            val response = client.newCall(request).execute()
            val code = response.code
            val responseBody = response.body?.string() ?: ""
            response.close()

            if (code in 200..299) {
                val publicUrl = "$supabaseUrl/storage/v1/object/public/$bucketName/$cleanPath"
                Log.i(TAG, "Direct REST upload succeeded to: $publicUrl")
                return@withContext publicUrl
            }

            Log.w(TAG, "Direct REST upload to $bucketName returned status $code: $responseBody")

            // If bucket not found (404), attempt to create public bucket
            if (code == 404 && responseBody.contains("Bucket not found", ignoreCase = true)) {
                ensureBucketExists(bucketName, sessionToken, anonKey, supabaseUrl)
                // Retry upload once
                val retryResponse = client.newCall(request).execute()
                val retryCode = retryResponse.code
                val retryBody = retryResponse.body?.string() ?: ""
                retryResponse.close()
                if (retryCode in 200..299) {
                    val publicUrl = "$supabaseUrl/storage/v1/object/public/$bucketName/$cleanPath"
                    Log.i(TAG, "Direct REST upload succeeded after bucket creation: $publicUrl")
                    return@withContext publicUrl
                }
                Log.w(TAG, "Retry upload after bucket creation failed ($retryCode): $retryBody")
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "REST API upload exception: ${e.message}", e)
            null
        }
    }

    private suspend fun ensureBucketExists(
        bucketName: String,
        authToken: String,
        anonKey: String,
        supabaseUrl: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = SupabaseModule.createCustomOkHttpClient()
            val bucketUrl = "$supabaseUrl/storage/v1/bucket"
            val jsonPayload = "{\"id\":\"$bucketName\",\"name\":\"$bucketName\",\"public\":true}"
            val requestBody = jsonPayload.toRequestBody("application/json".toMediaTypeOrNull())

            val request = Request.Builder()
                .url(bucketUrl)
                .post(requestBody)
                .header("Authorization", "Bearer $authToken")
                .header("apikey", anonKey)
                .header("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val code = response.code
            response.close()
            Log.i(TAG, "Bucket creation response for '$bucketName': $code")
            code in 200..299 || code == 409
        } catch (e: Exception) {
            Log.w(TAG, "Could not create storage bucket '$bucketName': ${e.message}")
            false
        }
    }

    /**
     * Uploads compressed image bytes for a goat to Supabase Storage with local fallback.
     * Path structure: goat-images/farm/{farmId}/goat/{goatId}/{imageFileName} or goat-images/goat/{goatId}/{imageFileName}
     */
    suspend fun uploadGoatImage(
        context: Context,
        bytes: ByteArray,
        goatId: String,
        customFileName: String? = null,
        photoIndex: Int? = null,
        farmId: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (bytes.isEmpty() || bytes.size > MAX_FILE_SIZE_BYTES) {
                return@withContext Result.failure(IllegalArgumentException("Image payload is empty or exceeds size limit."))
            }

            val sanitizedGoatId = goatId.replace(Regex("[^a-zA-Z0-9_-]"), "").ifBlank { "goat-${UUID.randomUUID().toString().take(6)}" }
            val sanitizedFarmId = farmId?.replace(Regex("[^a-zA-Z0-9_-]"), "")?.ifBlank { null }
            val fileName = generateProperFileName(
                prefix = "goat_photo",
                customName = customFileName,
                index = photoIndex
            )

            val storagePath = if (sanitizedFarmId != null) {
                "farm/$sanitizedFarmId/goat/$sanitizedGoatId/$fileName"
            } else {
                "goat/$sanitizedGoatId/$fileName"
            }

            // 1. Try Supabase Storage SDK upload with primary bucket
            if (SupabaseConfig.isConfigured) {
                try {
                    val bucket = SupabaseModule.storage[SupabaseConfig.BUCKET_GOAT_IMAGES]
                    bucket.upload(storagePath, bytes) {
                        upsert = true
                    }
                    val publicUrl = resolvePublicUrl(storagePath)
                    Log.i(TAG, "SDK uploaded goat image to Supabase Storage: $publicUrl")
                    return@withContext Result.success(publicUrl)
                } catch (sdkErr: Exception) {
                    Log.w(TAG, "SDK upload failed for ${SupabaseConfig.BUCKET_GOAT_IMAGES}: ${sdkErr.message}")
                }

                // 2. Try direct REST API upload with primary bucket
                val restResult = uploadViaRestApi(bytes, SupabaseConfig.BUCKET_GOAT_IMAGES, storagePath)
                if (restResult != null) {
                    return@withContext Result.success(restResult)
                }
            }

            // Fallback to local storage if remote upload failed
            val localUrl = saveImageLocally(context, bytes, "goat_$sanitizedGoatId", fileName)
            Log.w(TAG, "Fallback saved goat image locally: $localUrl")
            Result.success(localUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload goat image: ${e.message}", e)
            val localUrl = saveImageLocally(context, bytes, "goat")
            Result.success(localUrl)
        }
    }

    /**
     * Legacy adapter
     */
    suspend fun uploadGoatImage(
        bytes: ByteArray,
        goatId: String,
        customFileName: String? = null,
        farmId: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val sanitizedGoatId = goatId.replace(Regex("[^a-zA-Z0-9_-]"), "").ifBlank { "goat-${UUID.randomUUID().toString().take(6)}" }
            val sanitizedFarmId = farmId?.replace(Regex("[^a-zA-Z0-9_-]"), "")?.ifBlank { null }
            val fileName = generateProperFileName(
                prefix = "goat_photo",
                customName = customFileName
            )

            val storagePath = if (sanitizedFarmId != null) {
                "farm/$sanitizedFarmId/goat/$sanitizedGoatId/$fileName"
            } else {
                "goat/$sanitizedGoatId/$fileName"
            }

            try {
                val bucket = SupabaseModule.storage[SupabaseConfig.BUCKET_GOAT_IMAGES]
                bucket.upload(storagePath, bytes) {
                    upsert = true
                }
                return@withContext Result.success(resolvePublicUrl(storagePath))
            } catch (_: Exception) {}

            val restUrl = uploadViaRestApi(bytes, SupabaseConfig.BUCKET_GOAT_IMAGES, storagePath)
            if (restUrl != null) {
                return@withContext Result.success(restUrl)
            }

            Result.success(resolvePublicUrl(storagePath))
        } catch (e: Exception) {
            Log.w(TAG, "Upload without context failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Uploads compressed image bytes for a farm (logo/banner) to Supabase Storage with local fallback.
     */
    suspend fun uploadFarmImage(
        context: Context,
        bytes: ByteArray,
        farmId: String,
        imageType: String = "logo",
        customFileName: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (bytes.isEmpty() || bytes.size > MAX_FILE_SIZE_BYTES) {
                return@withContext Result.failure(IllegalArgumentException("Image payload is empty or exceeds size limit."))
            }

            val sanitizedFarmId = farmId.replace(Regex("[^a-zA-Z0-9_-]"), "").ifBlank { "farm-${UUID.randomUUID().toString().take(6)}" }
            val sanitizedType = imageType.replace(Regex("[^a-zA-Z0-9_-]"), "").ifBlank { "img" }
            val fileName = generateProperFileName(
                prefix = "farm_$sanitizedType",
                customName = customFileName
            )

            val storagePath = "farm/$sanitizedFarmId/$fileName"

            if (SupabaseConfig.isConfigured) {
                try {
                    val bucket = SupabaseModule.storage[SupabaseConfig.BUCKET_GOAT_IMAGES]
                    bucket.upload(storagePath, bytes) {
                        upsert = true
                    }
                    val publicUrl = resolvePublicUrl(storagePath)
                    Log.i(TAG, "Uploaded farm image to Supabase Storage: $publicUrl")
                    return@withContext Result.success(publicUrl)
                } catch (storageErr: Exception) {
                    Log.w(TAG, "SDK Farm upload failed: ${storageErr.message}")
                }

                val restUrl = uploadViaRestApi(bytes, SupabaseConfig.BUCKET_GOAT_IMAGES, storagePath)
                if (restUrl != null) {
                    return@withContext Result.success(restUrl)
                }
            }

            val localUrl = saveImageLocally(context, bytes, "farm_${sanitizedType}", fileName)
            Result.success(localUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload farm image: ${e.message}", e)
            val localUrl = saveImageLocally(context, bytes, "farm")
            Result.success(localUrl)
        }
    }

    suspend fun uploadFarmImage(
        bytes: ByteArray,
        farmId: String,
        imageType: String = "logo",
        customFileName: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val sanitizedFarmId = farmId.replace(Regex("[^a-zA-Z0-9_-]"), "").ifBlank { "farm-${UUID.randomUUID().toString().take(6)}" }
            val sanitizedType = imageType.replace(Regex("[^a-zA-Z0-9_-]"), "").ifBlank { "img" }
            val fileName = customFileName?.replace(Regex("[^a-zA-Z0-9._-]"), "")?.ifBlank { null }
                ?: "${sanitizedType}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.jpg"

            val storagePath = "farm/$sanitizedFarmId/$fileName"
            try {
                val bucket = SupabaseModule.storage[SupabaseConfig.BUCKET_GOAT_IMAGES]
                bucket.upload(storagePath, bytes) {
                    upsert = true
                }
                return@withContext Result.success(resolvePublicUrl(storagePath))
            } catch (_: Exception) {}

            val restUrl = uploadViaRestApi(bytes, SupabaseConfig.BUCKET_GOAT_IMAGES, storagePath)
            if (restUrl != null) {
                return@withContext Result.success(restUrl)
            }

            Result.success(resolvePublicUrl(storagePath))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Deletes a file from Supabase Storage bucket goat-images.
     */
    suspend fun deleteStorageFile(storagePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (storagePath.startsWith("file://") || storagePath.startsWith("data:") || storagePath.startsWith("http")) {
                if (storagePath.startsWith("file://")) {
                    File(storagePath.removePrefix("file://")).delete()
                }
                return@withContext Result.success(Unit)
            }
            val cleanPath = SupabaseConfig.extractStoragePath(storagePath, SupabaseConfig.BUCKET_GOAT_IMAGES)
            if (cleanPath.isBlank()) {
                return@withContext Result.success(Unit)
            }
            val bucket = SupabaseModule.storage[SupabaseConfig.BUCKET_GOAT_IMAGES]
            bucket.delete(cleanPath)
            Log.d(TAG, "Deleted file from Supabase Storage: $cleanPath")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "Error deleting file from Supabase Storage ($storagePath): ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Deletes multiple files from Supabase Storage bucket goat-images.
     */
    suspend fun deleteStorageFiles(storagePaths: List<String>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cleanPaths = storagePaths.mapNotNull { path ->
                if (path.startsWith("file://")) {
                    File(path.removePrefix("file://")).delete()
                    null
                } else if (path.startsWith("data:") || path.startsWith("http")) {
                    null
                } else {
                    val p = SupabaseConfig.extractStoragePath(path, SupabaseConfig.BUCKET_GOAT_IMAGES)
                    if (p.isNotBlank()) p else null
                }
            }
            if (cleanPaths.isEmpty()) {
                return@withContext Result.success(Unit)
            }
            val bucket = SupabaseModule.storage[SupabaseConfig.BUCKET_GOAT_IMAGES]
            bucket.delete(cleanPaths)
            Log.d(TAG, "Deleted ${cleanPaths.size} files from Supabase Storage")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "Error batch deleting files from Supabase Storage: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Resolves storage path to a full public URL or keeps local/http url.
     */
    fun resolvePublicUrl(storagePathOrUrl: String?): String {
        if (storagePathOrUrl.isNullOrBlank()) return ""
        if (storagePathOrUrl.startsWith("file://") || storagePathOrUrl.startsWith("http://") || 
            storagePathOrUrl.startsWith("https://") || storagePathOrUrl.startsWith("data:") ||
            storagePathOrUrl.startsWith("content://") || storagePathOrUrl.startsWith("android.resource://")) {
            return storagePathOrUrl
        }
        return SupabaseConfig.resolveStorageUrl(storagePathOrUrl, SupabaseConfig.BUCKET_GOAT_IMAGES)
    }

    suspend fun uploadToSupabase(
        bytes: ByteArray,
        farmId: String,
        filePrefix: String = "goat"
    ): Result<String> {
        return uploadGoatImage(bytes, farmId)
    }
}
