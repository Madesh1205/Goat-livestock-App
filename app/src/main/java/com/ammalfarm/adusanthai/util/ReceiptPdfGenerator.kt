package com.ammalfarm.adusanthai.util

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.ammalfarm.adusanthai.model.ListingPayment
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReceiptPdfGenerator {

    fun generateReceiptPdf(context: Context, payment: ListingPayment): File? {
        return try {
            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 size at 72 DPI
            val page = pdfDocument.startPage(pageInfo)
            val canvas: Canvas = page.canvas

            val paint = Paint().apply {
                isAntiAlias = true
            }

            // Colors
            val primaryColor = Color.parseColor("#00695C") // Teal
            val darkColor = Color.parseColor("#212121")
            val grayColor = Color.parseColor("#757575")
            val lightGray = Color.parseColor("#E0E0E0")
            val greenColor = Color.parseColor("#2E7D32")

            // Background White
            canvas.drawColor(Color.WHITE)

            // Header Banner
            paint.color = primaryColor
            canvas.drawRect(0f, 0f, 595f, 100f, paint)

            paint.color = Color.WHITE
            paint.textSize = 22f
            paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText("ADU SANTHAI LIVESTOCK MARKET", 40f, 45f, paint)

            paint.textSize = 12f
            paint.typeface = Typeface.DEFAULT
            canvas.drawText("Official Tax Invoice & Payment Receipt (Ammal Farm Enterprise)", 40f, 70f, paint)
            canvas.drawText("Support: +91 63808 98358 | adusanthai@ammalfarm.com", 40f, 88f, paint)

            // Receipt Title / Number
            paint.color = darkColor
            paint.textSize = 18f
            paint.typeface = Typeface.DEFAULT_BOLD
            val receiptNo = payment.receiptNumber ?: "RCPT-${payment.id.take(8).uppercase()}"
            canvas.drawText("RECEIPT #$receiptNo", 40f, 140f, paint)

            paint.textSize = 11f
            paint.color = grayColor
            paint.typeface = Typeface.DEFAULT
            val dateFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            val dateStr = dateFormat.format(Date(payment.createdAt))
            canvas.drawText("Issue Date: $dateStr", 40f, 160f, paint)

            // Status Badge Box
            paint.color = Color.parseColor("#E8F5E9")
            canvas.drawRoundRect(400f, 125f, 555f, 165f, 6f, 6f, paint)
            paint.color = greenColor
            paint.textSize = 12f
            paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText("STATUS: ${payment.status.name}", 415f, 150f, paint)

            // Divider line
            paint.color = lightGray
            paint.strokeWidth = 1f
            canvas.drawLine(40f, 185f, 555f, 185f, paint)

            // Farm & Payer Details Section
            paint.color = darkColor
            paint.textSize = 13f
            paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText("FARM & TRANSACTION DETAILS", 40f, 215f, paint)

            paint.textSize = 12f
            paint.typeface = Typeface.DEFAULT
            var currentY = 240f
            
            fun drawRow(label: String, value: String) {
                paint.color = grayColor
                paint.typeface = Typeface.DEFAULT
                canvas.drawText(label, 40f, currentY, paint)
                paint.color = darkColor
                paint.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText(value, 220f, currentY, paint)
                paint.typeface = Typeface.DEFAULT
                currentY += 25f
            }

            drawRow("Farm Name:", payment.farmName.ifBlank { "Partner Farm" })
            drawRow("Transaction Type:", payment.paymentType.replace("_", " "))
            drawRow("Payment Method:", payment.paymentMethod)
            drawRow("Payment Ref / UTR:", payment.razorpayPaymentId ?: "N/A (Manual)")
            drawRow("Slots Granted:", "${payment.slotsAdded} Goat Listing Slot(s)")
            drawRow("Currency / Amount:", "${payment.currency} ₹${String.format(Locale.getDefault(), "%.2f", payment.amount)}")
            drawRow("Admin Notes / Memo:", payment.notes ?: "Partner fee payment recorded successfully.")

            // Table / Summary Box
            currentY += 15f
            paint.color = Color.parseColor("#F5F5F5")
            canvas.drawRect(40f, currentY, 555f, currentY + 70f, paint)
            
            paint.color = primaryColor
            paint.textSize = 12f
            paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText("TOTAL AMOUNT PAID", 60f, currentY + 25f, paint)

            paint.textSize = 22f
            paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText("₹${String.format(Locale.getDefault(), "%.2f", payment.amount)}", 60f, currentY + 55f, paint)

            // Footer Section
            val footerY = 740f
            paint.color = lightGray
            canvas.drawLine(40f, footerY - 15f, 555f, footerY - 15f, paint)

            paint.color = grayColor
            paint.textSize = 10f
            paint.typeface = Typeface.DEFAULT
            canvas.drawText("This is a computer-generated official receipt issued by Adu Santhai Super Admin.", 40f, footerY + 5f, paint)
            canvas.drawText("Verified under Ammal Farm Livestock Registry. No physical signature required.", 40f, footerY + 20f, paint)
            canvas.drawText("For verification & support, contact: +91 63808 98358", 40f, footerY + 35f, paint)

            pdfDocument.finishPage(page)

            val file = File(context.cacheDir, "AduSanthai_Receipt_${payment.receiptNumber ?: payment.id.take(8)}.pdf")
            val fos = FileOutputStream(file)
            pdfDocument.writeTo(fos)
            pdfDocument.close()
            fos.close()

            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun openPdf(context: Context, file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun sharePdf(context: Context, file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Adu Santhai Official Receipt")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Share Receipt PDF via").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
