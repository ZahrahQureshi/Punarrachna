package com.example.punarrachna

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

// ---------------------------------------------------------------------
// Data Models & Targeted Re-Issuance Authorities
// ---------------------------------------------------------------------

enum class DocumentCategory(
    val label: String,
    val formTitle: String,
    val authority: String,
    val emoji: String,
    val submissionPoints: List<String>
) {
    DRIVING_LICENSE("Driving Licence", "FORM 2 - APPLICATION FOR DUPLICATE LICENCE", "Ministry of Road Transport & Highways / Parivahan", "🚗", listOf("Local RTO Office", "Parivahan Portal")),
    VOTER_ID("Voter ID / EPIC", "FORM 8 - REPLACEMENT OF EPIC", "Election Commission of India", "🗳️", listOf("District Election Office", "BLO Office")),
    AADHAAR("Aadhaar Identity", "ENROLMENT / CORRECTION / UPDATE FORM", "UIDAI", "🧬", listOf("Aadhaar Seva Kendra", "Post Office")),
    MARKSHEET("Board Marksheet", "SSC/HSC DUPLICATE MARKSHEET / CERTIFICATE", "Maharashtra State Board (SSC/HSC)", "🎓", listOf("Board Divisional Office")),
    PASSPORT("Passport", "PASSPORT RE-ISSUE AFTER LOSS (ANNEXURE F)", "Ministry of External Affairs / Passport Seva", "🛂", listOf("Passport Seva Kendra")),
    PAN("PAN Card", "REPRINT OF EXISTING PAN", "Income Tax Department", "💳", listOf("NSDL / UTIITSL Center")),
    INSURANCE("Insurance Policy", "DUPLICATE POLICY APPLICATION", "Your Insurer (IRDAI Regulated)", "📜", listOf("Insurance Branch Office")),
    UNKNOWN("General Fragment", "IDENTITY RECONSTRUCTION AFFIDAVIT", "Local Revenue Authority", "📂", listOf("Tehsildar Office"))
}

enum class EvidenceSource(val label: String, val color: Color) {
    SMS("SMS Fragment", Color(0xFF1976D2)),
    DIGILOCKER("DigiLocker Verified", Color(0xFF388E3C)),
    KYC_STATEMENT("Verified KYC", Color(0xFFF57C00))
}

data class ScannedDocument(
    val id: Long,
    val category: DocumentCategory,
    val extractedValue: String,
    val sender: String,
    val fullMessage: String,
    val timestamp: Long,
    val source: EvidenceSource = EvidenceSource.SMS
)

data class IdentityChain(
    val score: Int,
    val confidenceLevel: String,
    val crossLinks: List<String>,
    val recommendations: List<String>
)

// ---------------------------------------------------------------------
// Fragment Fusion Engine
// ---------------------------------------------------------------------

object FusionEngine {
    fun calculateIdentityConfidence(documents: List<ScannedDocument>): IdentityChain {
        if (documents.isEmpty()) return IdentityChain(0, "None", emptyList(), listOf("Scan device for digital fragments"))
        var score = 20
        val categories = documents.map { it.category }.toSet()
        val sources = documents.map { it.source }.toSet()
        val links = mutableListOf<String>()
        score += (categories.size * 10).coerceAtMost(30)
        if (sources.contains(EvidenceSource.DIGILOCKER)) { score += 25; links.add("DigiLocker Verification Verified") }
        if (sources.contains(EvidenceSource.KYC_STATEMENT)) { score += 15; links.add("Bank KYC Linkage Detected") }
        
        val confidence = when {
            score >= 85 -> "Beyond Reasonable Doubt"
            score >= 65 -> "High Confidence"
            score >= 40 -> "Partial Verification"
            else -> "Preliminary Evidence"
        }
        return IdentityChain(score.coerceAtMost(98), confidence, links, listOf("Generate Recovery Packets"))
    }
}

// ---------------------------------------------------------------------
// Evidence Scanners (Fixing Classification Bug)
// ---------------------------------------------------------------------

object SmsDocumentScanner {
    private val aadhaarRegex = Regex("""aadha?ar""", RegexOption.IGNORE_CASE)
    private val panRegex = Regex("""\b[A-Z]{5}\d{4}[A-Z]\b""")
    private val passportRegex = Regex("""passport""", RegexOption.IGNORE_CASE)
    // Refined DL regex to avoid collision with Passport ref numbers
    private val dlRegex = Regex("""\b[A-Z]{2}\d{2}\s?(19|20)\d{2}\d{7,11}\b""")
    private val epicRegex = Regex("""\b[A-Z]{3}\d{7}\b""")
    private val marksheetRegex = Regex("""(marksheet|seat\s*(no|number)|roll\s*(no|number))""", RegexOption.IGNORE_CASE)

    fun scanInbox(context: Context): List<ScannedDocument> {
        val messages = mutableListOf<ScannedDocument>()
        val uri = Uri.parse("content://sms/inbox")
        val projection = arrayOf("_id", "address", "body", "date")
        try {
            context.contentResolver.query(uri, projection, null, null, "date DESC")?.use { c ->
                while (c.moveToNext()) {
                    val body = c.getString(2) ?: continue
                    val category = classify(body) ?: continue
                    messages.add(ScannedDocument(c.getLong(0), category, "Extracted Proof", c.getString(1), body, c.getLong(3)))
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return messages
    }

    private fun classify(body: String): DocumentCategory? {
        // Priority to Passport to fix the DL collision bug
        if (passportRegex.containsMatchIn(body)) return DocumentCategory.PASSPORT
        if (panRegex.containsMatchIn(body)) return DocumentCategory.PAN
        if (dlRegex.containsMatchIn(body)) return DocumentCategory.DRIVING_LICENSE
        if (epicRegex.containsMatchIn(body)) return DocumentCategory.VOTER_ID
        if (aadhaarRegex.containsMatchIn(body)) return DocumentCategory.AADHAAR
        if (marksheetRegex.containsMatchIn(body)) return DocumentCategory.MARKSHEET
        return null
    }

    fun getMockSurvivorData(): List<ScannedDocument> {
        val now = System.currentTimeMillis()
        return listOf(
            ScannedDocument(201, DocumentCategory.MARKSHEET, "Seat: M128374", "SSC-BOARD", "Results for Seat M128374: Eng-88, Math-92. Year: 2019. Center: 0822.", now - 86400000L * 100),
            ScannedDocument(202, DocumentCategory.PAN, "ABCDE1234F", "IT-DEPT", "Your PAN ABCDE1234F is verified for E-filing.", now - 86400000L * 150),
            ScannedDocument(203, DocumentCategory.PASSPORT, "File BOMW0662", "PASSPORT", "Passport Application BOMW0662 is processed.", now - 86400000L * 200)
        )
    }
}

object MockDigiLockerScanner {
    fun fetchVerifiedRecords(): List<ScannedDocument> {
        val now = System.currentTimeMillis()
        return listOf(
            ScannedDocument(301, DocumentCategory.DRIVING_LICENSE, "MH12 20140082731", "DigiLocker", "Verified DL MH12 20140082731. Owner: Suraj Sharma.", now, EvidenceSource.DIGILOCKER),
            ScannedDocument(302, DocumentCategory.VOTER_ID, "XYZ1234567", "DigiLocker", "Verified EPIC XYZ1234567.", now, EvidenceSource.DIGILOCKER)
        )
    }
}

// ---------------------------------------------------------------------
// High-Fidelity Official PDF Generator (Exact Layouts)
// ---------------------------------------------------------------------

object DocumentPdfGenerator {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN_X = 50f
    private const val CONTENT_WIDTH = PAGE_WIDTH - (2 * MARGIN_X)

    fun generatePacket(context: Context, name: String, address: String, extra: Map<String, String>, chain: IdentityChain, allDocs: List<ScannedDocument>, selectedCategory: DocumentCategory): Result<String> {
        return try {
            val pdfDocument = PdfDocument()
            val categoryDocs = allDocs.filter { it.category == selectedCategory }
            
            // PAGE 0: Evidence Summary (Actual SMS Proof)
            val p0 = pdfDocument.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 0).create())
            drawEvidencePage(p0.canvas, name, chain, categoryDocs)
            pdfDocument.finishPage(p0)

            // PAGE 1: Official Government Form
            val p1 = pdfDocument.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create())
            drawOfficialForm(p1.canvas, selectedCategory, name, address, extra)
            pdfDocument.finishPage(p1)

            // PAGE 2: Annexure F (For Passport)
            if (selectedCategory == DocumentCategory.PASSPORT) {
                val p2 = pdfDocument.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 2).create())
                drawAnnexureF(p2.canvas, name, address)
                pdfDocument.finishPage(p2)
            }

            val fileName = "${selectedCategory.name}_Recovery_Packet_${System.currentTimeMillis()}.pdf"
            val outputStream = ByteArrayOutputStream()
            pdfDocument.writeTo(outputStream)
            pdfDocument.close()
            Result.success(savePdfBytes(context, fileName, outputStream.toByteArray()))
        } catch (e: Exception) { Result.failure(e) }
    }

    private fun drawEvidencePage(canvas: Canvas, name: String, chain: IdentityChain, docs: List<ScannedDocument>) {
        val titlePaint = Paint().apply { textSize = 14f; isFakeBoldText = true }
        val bodyPaint = Paint().apply { textSize = 10f }
        var y = 60f
        canvas.drawText("PUNARRACHNA - DISASTER IDENTITY RECOVERY EVIDENCE", MARGIN_X, y, titlePaint)
        y += 30f
        canvas.drawText("IDENTIFICATION FOR: $name", MARGIN_X, y, bodyPaint)
        y += 20f
        canvas.drawText("IDENTITY CONFIDENCE SCORE: ${chain.score}%", MARGIN_X, y, bodyPaint)
        y += 40f
        canvas.drawText("ACTUAL DIGITAL FRAGMENT PROOF (FROM DEVICE STORAGE):", MARGIN_X, y, titlePaint.apply { textSize = 11f })
        y += 20f
        docs.forEach { doc ->
            y = drawWrapped(canvas, "• [${doc.source.label}] From ${doc.sender}: \"${doc.fullMessage}\"", MARGIN_X, y, CONTENT_WIDTH, bodyPaint, 15f)
            y += 10f
        }
    }

    private fun drawOfficialForm(canvas: Canvas, cat: DocumentCategory, name: String, address: String, extra: Map<String, String>) {
        val titlePaint = Paint().apply { textSize = 13f; isFakeBoldText = true; color = AndroidColor.BLACK }
        val bodyPaint = Paint().apply { textSize = 9f; color = AndroidColor.BLACK }
        val boxPaint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.5f; color = AndroidColor.BLACK }
        var y = 60f

        canvas.drawText(cat.authority, MARGIN_X, y, bodyPaint.apply { isFakeBoldText = true })
        y += 15f
        canvas.drawText(cat.formTitle, MARGIN_X, y, titlePaint)
        y += 40f

        fun drawField(label: String, value: String) {
            canvas.drawText(label, MARGIN_X, y, bodyPaint)
            y += 4f
            canvas.drawRect(MARGIN_X, y, PAGE_WIDTH - MARGIN_X, y + 20f, boxPaint)
            canvas.drawText(value.uppercase(), MARGIN_X + 5f, y + 15f, bodyPaint)
            y += 30f
        }

        when(cat) {
            DocumentCategory.DRIVING_LICENSE -> {
                drawField("1. Applicant Name and personal details", name)
                drawField("2. Date/place of birth", extra["dob_pob"] ?: "")
                drawField("3. Parent/guardian details", extra["parent_details"] ?: "")
                drawField("4. Present and permanent address", address + " / " + (extra["permanent_address"] ?: ""))
                drawField("5. Contact details", extra["contact"] ?: "")
                drawField("6. Existing licence number", extra["license_no"] ?: "")
                drawField("7. Vehicle class", extra["vehicle_class"] ?: "")
                drawField("8. Issuing licensing authority", extra["issuing_auth"] ?: "")
                drawField("9. Licence validity", extra["validity"] ?: "")
                drawField("10. Documents attached", "DIGITAL EVIDENCE LOG")
                drawField("11. Police complaint copy (Ref No)", extra["fir_ref"] ?: "")
            }
            DocumentCategory.VOTER_ID -> {
                drawField("1. Applicant name", name)
                drawField("2. EPIC number", extra["epic_no"] ?: "")
                drawField("3. Aadhaar details or declaration", extra["aadhaar_details"] ?: "")
                drawField("4. Mobile number", extra["mobile"] ?: "")
                drawField("5. Email", extra["email"] ?: "")
                canvas.drawText("[X] Issue of Replacement EPIC without correction", MARGIN_X, y, bodyPaint); y += 20f
                drawField("7. FIR/police report copy for lost EPIC", extra["fir_ref"] ?: "")
            }
            DocumentCategory.AADHAAR -> {
                drawField("1. Resident/NRI selection", "RESIDENT")
                drawField("2. Pre-enrolment ID (if applicable)", "N/A")
                drawField("3. Aadhaar number for update", extra["aadhaar_no"] ?: "")
                drawField("4. Update purpose", "BIOMETRIC/ADDRESS/DOB")
                drawField("5. Full name", name)
                drawField("6. Gender", extra["gender"] ?: "")
                drawField("7. Age/DOB", extra["age_dob"] ?: "")
                drawField("8. Address", address)
                drawField("9. Email/mobile", extra["contact"] ?: "")
                drawField("10. Parent/guardian/HOF details", extra["hof_details"] ?: "")
                drawField("11. Identity/address/DOB document details", "VERIFIED FRAGMENTS")
            }
            DocumentCategory.MARKSHEET -> {
                drawField("1. SSC or HSC examination", extra["exam_type"] ?: "SSC")
                drawField("2. Seat number/examination details", extra["seat_no"] ?: "")
                drawField("3. Year and session", extra["year_session"] ?: "")
                drawField("4. Student details (Full Name)", name)
                drawField("5. Mother's Name", extra["mother_name"] ?: "")
                drawField("6. Contact/OTP verification", extra["contact"] ?: "")
                drawField("7. Declaration/affidavit where applicable", "ATTACHED")
                drawField("8. Payment/application details", "PENDING AT COUNTER")
            }
            DocumentCategory.PASSPORT -> {
                drawField("1. Existing passport details", extra["old_passport"] ?: "")
                drawField("2. Reason: lost/stolen passport", "LOST IN DISASTER")
                drawField("3. Personal particulars", name)
                drawField("4. Current address", address)
                drawField("5. Previous passport details", extra["prev_passport"] ?: "")
                drawField("6. Police report/FIR details", extra["fir_ref"] ?: "")
                drawField("7. Supporting documents", "ANNEXURE F + FRAGMENTS")
                drawField("8. Online declaration", "COMPLETED VIA PUNARRACHNA")
                drawField("9. Application/appointment details", "TO BE SCHEDULED")
            }
            DocumentCategory.PAN -> {
                drawField("1. Existing PAN number", extra["pan_no"] ?: "")
                drawField("2. Name as per PAN", name)
                drawField("3. Date of birth", extra["dob"] ?: "")
                drawField("4. Contact details", extra["contact"] ?: "")
                drawField("5. Required address/details", address)
                drawField("6. Identity/OTP verification", "BIO-METRIC AT CENTER")
                drawField("7. Reprint and delivery details", "SPEED POST")
            }
            DocumentCategory.INSURANCE -> {
                drawField("1. Policy number", extra["policy_no"] ?: "")
                drawField("2. Policyholder name", name)
                drawField("3. Policy type", extra["policy_type"] ?: "")
                drawField("4. Reason: original policy lost", "DISASTER DESTRUCTION")
                drawField("5. Date/place of loss", extra["loss_date_place"] ?: "")
                drawField("6. ID/contact details", address)
                drawField("7. Declaration/undertaking", "SUBMITTED")
                drawField("9. FIR/police report", extra["fir_ref"] ?: "")
            }
            else -> {
                drawField("Claimant Identification", name)
                drawField("Evidence Summary", address)
            }
        }
        
        y += 40f
        canvas.drawText("Declaration: I hereby declare that the above information is true to the best of my knowledge.", MARGIN_X, y, bodyPaint)
        y += 60f
        canvas.drawText("Signature/Thumbprint: _______________________", MARGIN_X, y, bodyPaint)
        canvas.drawText("(Manual Signature Required After Print)", MARGIN_X, y + 12f, bodyPaint.apply { textSize = 7f; isFakeBoldText = false })
    }

    private fun drawAnnexureF(canvas: Canvas, name: String, address: String) {
        val titlePaint = Paint().apply { textSize = 12f; isFakeBoldText = true }
        val bodyPaint = Paint().apply { textSize = 10f }
        var y = 60f
        canvas.drawText("ANNEXURE 'F' - SPECIMEN DECLARATION OF APPLICANT", MARGIN_X, y, titlePaint)
        y += 40f
        val text = "I, $name, residing at $address, solemnly affirm that my passport has been lost in a disaster event. I affirm that I will take utmost care of my passport if issued. I declare that I have not travelled on the lost passport after its loss."
        y = drawWrapped(canvas, text, MARGIN_X, y, CONTENT_WIDTH, bodyPaint, 18f)
        y += 60f
        canvas.drawText("Date: ________________", MARGIN_X, y, bodyPaint)
        canvas.drawText("Place: ________________", MARGIN_X + 200f, y, bodyPaint)
        y += 60f
        canvas.drawText("Signature of Applicant: _______________________", MARGIN_X, y, bodyPaint)
    }

    private fun drawWrapped(canvas: Canvas, text: String, x: Float, startY: Float, maxWidth: Float, paint: Paint, lh: Float): Float {
        var y = startY
        val words = text.split(" ")
        var line = StringBuilder()
        for (word in words) {
            val test = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(test) > maxWidth) {
                canvas.drawText(line.toString(), x, y, paint)
                y += lh
                line = StringBuilder(word)
            } else line = StringBuilder(test)
        }
        canvas.drawText(line.toString(), x, y, paint)
        return y + lh
    }

    private fun savePdfBytes(context: Context, fileName: String, bytes: ByteArray): String {
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            resolver.openOutputStream(uri!!)?.use { it.write(bytes) }
            "Downloads/$fileName"
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(dir, fileName)
            FileOutputStream(file).use { it.write(bytes) }
            file.absolutePath
        }
    }
}

// ---------------------------------------------------------------------
// MainActivity & Interactive UI
// ---------------------------------------------------------------------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PunarrachnaApp() }
    }
}

@Composable
fun PunarrachnaApp() {
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF1B5E20), secondary = Color(0xFF2E7D32))) {
        Surface(modifier = Modifier.fillMaxSize()) { PunarrachnaScreen() }
    }
}

@Composable
fun PunarrachnaScreen() {
    val context = LocalContext.current
    var hasSmsPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) }
    var scannedDocs by remember { mutableStateOf<List<ScannedDocument>>(emptyList()) }
    var chain by remember { mutableStateOf(FusionEngine.calculateIdentityConfidence(emptyList())) }
    var selectedCategory by remember { mutableStateOf<DocumentCategory?>(null) }
    
    var name by remember { mutableStateOf("SURAJ SHARMA") }
    var address by remember { mutableStateOf("RELIEF CAMP #4, PUNE") }
    var showAllSms by remember { mutableStateOf(false) }

    val extraFields = remember { mutableStateMapOf<String, String>() }

    val smsPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasSmsPermission = it }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).padding(16.dp)) {
            Text("PUNARRACHNA", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        LazyColumn(modifier = Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { EvidenceScoreboard(chain, scannedDocs.size) }

            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                        Text("Identity Discovery", fontWeight = FontWeight.Bold)
                        Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { 
                                if (!hasSmsPermission) smsPermissionLauncher.launch(Manifest.permission.READ_SMS)
                                else scannedDocs = (scannedDocs + SmsDocumentScanner.scanInbox(context)).distinctBy { it.id }
                                chain = FusionEngine.calculateIdentityConfidence(scannedDocs)
                            }, modifier = Modifier.weight(1f)) { Text("Scan SMS") }
                            
                            Button(onClick = { 
                                scannedDocs = (scannedDocs + MockDigiLockerScanner.fetchVerifiedRecords()).distinctBy { it.id }
                                chain = FusionEngine.calculateIdentityConfidence(scannedDocs)
                            }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF388E3C))) { Text("DigiLocker") }
                        }
                        
                        Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { showAllSms = !showAllSms }, modifier = Modifier.weight(1f)) { 
                                Text(if (showAllSms) "Hide Proofs" else "Show All Discovery") 
                            }
                            OutlinedButton(onClick = { 
                                scannedDocs = (scannedDocs + SmsDocumentScanner.getMockSurvivorData()).distinctBy { it.id }
                                chain = FusionEngine.calculateIdentityConfidence(scannedDocs)
                            }, modifier = Modifier.weight(1f)) { Text("Simulate") }
                        }
                    }
                }
            }

            if (showAllSms) {
                item { Text("Extracted Digital Fragments", fontWeight = FontWeight.Bold) }
                items(scannedDocs) { doc -> FragmentRow(doc) }
            }

            if (scannedDocs.isNotEmpty()) {
                item { Text("Identified Recovery Chains", fontWeight = FontWeight.Bold) }
                val grouped = scannedDocs.groupBy { it.category }
                items(grouped.keys.toList()) { cat ->
                    RecoveryChainCard(cat, grouped[cat] ?: emptyList(), selectedCategory == cat) { selectedCategory = if (selectedCategory == cat) null else cat }
                }
            }
        }

        AnimatedVisibility(visible = selectedCategory != null) {
            Surface(tonalElevation = 8.dp, shadowElevation = 12.dp) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                    Text("Auto-fill Official Form: ${selectedCategory?.label}", fontWeight = FontWeight.Bold)
                    
                    LazyColumn(modifier = Modifier.heightIn(max = 300.dp).padding(vertical = 8.dp)) {
                        item {
                            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Full Name") }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Present Address") }, modifier = Modifier.fillMaxWidth())
                            
                            // Dynamic Fields Based on Category
                            when(selectedCategory) {
                                DocumentCategory.DRIVING_LICENSE -> {
                                    ExtraFieldInput("dob_pob", "Date/Place of Birth", extraFields)
                                    ExtraFieldInput("parent_details", "Parent/Guardian Details", extraFields)
                                    ExtraFieldInput("permanent_address", "Permanent Address", extraFields)
                                    ExtraFieldInput("contact", "Contact Details", extraFields)
                                    ExtraFieldInput("license_no", "Existing License No", extraFields)
                                    ExtraFieldInput("vehicle_class", "Vehicle Class", extraFields)
                                    ExtraFieldInput("issuing_auth", "Issuing Authority", extraFields)
                                    ExtraFieldInput("validity", "Licence Validity", extraFields)
                                    ExtraFieldInput("fir_ref", "Police Complaint Ref", extraFields)
                                }
                                DocumentCategory.VOTER_ID -> {
                                    ExtraFieldInput("epic_no", "EPIC Number", extraFields)
                                    ExtraFieldInput("aadhaar_details", "Aadhaar Details", extraFields)
                                    ExtraFieldInput("mobile", "Mobile Number", extraFields)
                                    ExtraFieldInput("email", "Email", extraFields)
                                    ExtraFieldInput("fir_ref", "FIR Reference", extraFields)
                                }
                                DocumentCategory.AADHAAR -> {
                                    ExtraFieldInput("aadhaar_no", "Aadhaar Number", extraFields)
                                    ExtraFieldInput("gender", "Gender", extraFields)
                                    ExtraFieldInput("age_dob", "Age / DOB", extraFields)
                                    ExtraFieldInput("contact", "Mobile / Email", extraFields)
                                    ExtraFieldInput("hof_details", "Parent/Guardian/HOF Details", extraFields)
                                }
                                DocumentCategory.MARKSHEET -> {
                                    ExtraFieldInput("exam_type", "Exam Type (SSC/HSC)", extraFields)
                                    ExtraFieldInput("seat_no", "Seat Number", extraFields)
                                    ExtraFieldInput("year_session", "Year & Session", extraFields)
                                    ExtraFieldInput("mother_name", "Mother's Name", extraFields)
                                    ExtraFieldInput("school_center", "School/Center Details", extraFields)
                                    ExtraFieldInput("contact", "Contact for OTP", extraFields)
                                }
                                DocumentCategory.PASSPORT -> {
                                    ExtraFieldInput("old_passport", "Existing Passport Details", extraFields)
                                    ExtraFieldInput("prev_passport", "Previous Passport Details", extraFields)
                                    ExtraFieldInput("fir_ref", "Police Report Ref", extraFields)
                                }
                                DocumentCategory.PAN -> {
                                    ExtraFieldInput("pan_no", "Existing PAN Number", extraFields)
                                    ExtraFieldInput("dob", "Date of Birth", extraFields)
                                    ExtraFieldInput("contact", "Contact Details", extraFields)
                                }
                                DocumentCategory.INSURANCE -> {
                                    ExtraFieldInput("policy_no", "Policy Number", extraFields)
                                    ExtraFieldInput("policy_type", "Policy Type", extraFields)
                                    ExtraFieldInput("loss_date_place", "Date/Place of Loss", extraFields)
                                    ExtraFieldInput("fir_ref", "FIR Reference", extraFields)
                                }
                                else -> {}
                            }
                        }
                    }
                    
                    Text("Note: Manual signature required on printed form.", fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp))
                    
                    Button(onClick = {
                        selectedCategory?.let { cat ->
                            DocumentPdfGenerator.generatePacket(context, name, address, extraFields, chain, scannedDocs, cat)
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Export Official Re-Issuance Packet") }
                }
            }
        }
    }
}

@Composable
fun ExtraFieldInput(key: String, label: String, extraFields: MutableMap<String, String>) {
    OutlinedTextField(
        value = extraFields[key] ?: "",
        onValueChange = { extraFields[key] = it },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun FragmentRow(doc: ScannedDocument) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = doc.source.color.copy(alpha = 0.05f))) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row {
                Text(doc.source.label, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = doc.source.color)
                Spacer(Modifier.width(8.dp))
                Text(doc.sender, fontSize = 10.sp, color = Color.Gray)
            }
            Text("\"${doc.fullMessage}\"", fontSize = 11.sp, fontStyle = FontStyle.Italic)
        }
    }
}

@Composable
fun EvidenceScoreboard(chain: IdentityChain, count: Int) {
    Card(elevation = CardDefaults.cardElevation(6.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Row {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Identity Fusion Score", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(chain.confidenceLevel, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Text("${chain.score}%", fontSize = 32.sp, fontWeight = FontWeight.Black)
            }
            LinearProgressIndicator(progress = { chain.score / 100f }, modifier = Modifier.fillMaxWidth().height(8.dp).padding(vertical = 8.dp), strokeCap = StrokeCap.Round)
            Text("Surviving fragments found: $count", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            chain.crossLinks.forEach { link -> Text("⛓️ $link", fontSize = 11.sp) }
        }
    }
}

@Composable
fun RecoveryChainCard(category: DocumentCategory, evidence: List<ScannedDocument>, isSelected: Boolean, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }, border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(category.emoji, fontSize = 28.sp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(category.label, fontWeight = FontWeight.Bold)
                    Text("${evidence.size} proof fragments discovered", fontSize = 11.sp)
                }
            }
            if (isSelected) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Exact SMS Fragments Found:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                evidence.forEach { doc ->
                    Text(
                        text = "• ${doc.sender}: \"${doc.fullMessage}\"",
                        fontSize = 10.sp,
                        fontStyle = FontStyle.Italic,
                        color = Color.DarkGray,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("Submission Guidance:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                category.submissionPoints.forEach { p -> Text("📍 $p", fontSize = 11.sp) }
            }
        }
    }
}
