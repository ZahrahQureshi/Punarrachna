# Punarrachna 
> **Rebuilding Identity After Disaster via Digital Forensic Fragments**

Punarrachna is a digital forensic bridge designed for disaster survivors (floods, fires, landslides) who have lost all physical identification. It solves the **"Identity Deadlock"**: when a survivor loses both their physical papers and their SIM card, making them unable to receive OTPs for standard digital recovery (like DigiLocker) because they lack a Proof of Identity (POI) to get a new SIM.

## 🚀 The Problem: The Identity Deadlock
In large-scale disasters, the poorest and least-documented households are hit hardest. Physical document folders are often the first things lost. 
- **The Catch-22:** To reissue an ID, you must prove you are who you say you are. 
- **The SIM Gap:** Standard digital recovery requires an OTP. To get a replacement SIM, you need the very POI you just lost.
- **The Result:** Months of bureaucratic struggle, lost wages, and missed relief funds.

## ✨ Our Solution
Punarrachna proves that while your paper is gone, your **digital footprint survived**.
The app performs a local, forensic scan of device archives (SMS, local KYC cache, partial records) to extract institutional fragments (Bank KYC, Exam Results, Insurance Alerts). It then:
1. **Cross-References** these fragments to build an **Identity Confidence Matrix**.
2. **Auto-Fills High-Fidelity Official Forms** (UIDAI, State Board, RTO, Passport) using extracted data.
3. **Generates a Legal Recovery Packet** (PDF) including a Statutory Disaster Loss Affidavit, ready for submission at relief camps.

## 🛠️ Tech Stack
*   **Language:** Kotlin (100% Type-safe)
*   **UI Framework:** Jetpack Compose (Modern Declarative UI)
*   **Graphics Engine:** Android Canvas API (Used for pixel-perfect official form recreation)
*   **PDF Engine:** `android.graphics.pdf.PdfDocument`
*   **Extraction:** Regex-based Forensic Heuristic Engine (matches institutional headers like `AD-UIDAI`, `SSC-BRD`)
*   **Storage:** Android MediaStore API (Scoped Storage compliant)
*   **Architecture:** Offline-First / Zero-Cloud (Ensures 100% data privacy for sensitive PII)

## 📋 Features
- **One-Tap Discovery:** Scans SMS archives for identity traces.
- **Identity Fusion Matrix:** Calculates a confidence score (e.g., 85% "Beyond Reasonable Doubt") based on multi-fragment consistency.
- **High-Fidelity Official Forms:** 
    - **Maharashtra State Board (SSC/HSC):** Recreates official duplicate marksheet forms with Seat No, Mother's Name, etc.
    - **Passport Re-issue:** Generates Annexure 'F' declarations.
    - **RTO (DL/RC):** Form 2 and Form 26 pre-filled for duplicate licenses/RCs.
    - **Voter ID (EPIC):** Form 8 for replacement IDs.
- **Verbatim SMS Proof:** Displays exact institutional messages to provide a digital forensic trail for government officers.
- **Simulation Mode:** Built-in survivor scenario to demonstrate "Fusion Logic" using realistic mock fragments.

## 🏛️ Social Impact
Punarrachna turns a 6-month bureaucratic nightmare into a **guided 2-day checklist**. It empowers the most vulnerable survivors to reclaim their legal existence and access critical aid, insurance, and re-employment opportunities.

## 🛡️ Privacy & Security
**Zero-Cloud Policy:** All data extraction, fusion, and PDF generation happen **entirely on-device**. No personal data ever leaves the survivor's phone, making it ideal for low-connectivity disaster zones and highly sensitive identity data.
