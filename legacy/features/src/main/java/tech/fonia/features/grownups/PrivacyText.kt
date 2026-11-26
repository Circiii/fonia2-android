package tech.fonia.features.grownups

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/** Who answers for the app and where to find them (iOS `LegalInfo`). */
object LegalInfo {
    const val OPERATOR_NAME = "Dulgheru Ștefan Alexandru"
    const val CONTACT_EMAIL = "stdulgh@gmail.com"

    /** The privacy policy on the web. */
    const val PRIVACY_URL = "https://www.getfonia.com/confidentialitate"

    /** A page where adults can ask for help. */
    const val SUPPORT_URL = "https://www.getfonia.com/ajutor"

    /** "1.0 (12)": the version name and the build, as iOS shows them. */
    fun version(context: Context): String {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull() ?: return "?"
        val build = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        return "${info.versionName ?: "?"} ($build)"
    }

    /**
     * Opens [url] in the phone's browser: the browser fetches the page, not Fonia, whose one use of the internet is
     * the install code ([tech.fonia.features.app.InstallCount]). False when no app can open it.
     */
    fun open(context: Context, url: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

/**
 * What Fonia keeps and where it goes, in the words an adult reads in the app: four lines at the first run, the whole
 * text in the settings. The wording is iOS `PrivacyText`'s where the two apps keep the same things; the sections only
 * Android has (the voice numbers, the reading probes, the adaptive pilot) are added, and the model download, which
 * Android doesn't have, is left out. Every sentence has to stay true to the code; the comments say where.
 */
object PrivacyText {
    data class Section(val title: String, val body: String)

    /** The install code in one line: in the summary, and on an adult learner's setup page, who skips the summary. */
    const val INSTALL_LINE = "După o zi, trimitem o dată un cod făcut la întâmplare, ca să știm câte instalări sunt. Îl poți opri din Setări."

    /** The first run's short version. */
    val summary = listOf(
        "Vocea ta rămâne pe telefon.",
        "Camera pornește doar la Oglindă și nu păstrează nicio imagine.",
        INSTALL_LINE,
        "Fără cont, fără reclame. Poți șterge tot oricând, din Setări.",
    )

    /** [withReading]: the reading probes, a draft shown only in debug builds or with the drafts flag. */
    fun sections(withReading: Boolean): List<Section> = listOfNotNull(
        Section(
            "Ce rămâne pe telefon",
            "Profilurile, încercările, notițele și înregistrările stau pe telefonul tău. Fonia nu are cont, nu are reclame și nu măsoară cum o folosești.",
        ),
        // INTERNET is in the manifest for InstallCount alone. ACCESS_NETWORK_STATE stays removed, so the model fetchers
        // find no network, and check<Variant>OnnxRuntime fails the build if it comes back.
        Section(
            "Internetul",
            "Tot ce îi trebuie Foniei vine odată cu aplicația. Pe internet trimite doar codul de la „Numărul de instalări”, mai jos, și nimic altceva. Linkurile din Setări se deschid în browser.",
        ),
        Section(
            "Microfonul",
            "Fonia ascultă doar în timpul unui exercițiu. Vocea se analizează pe telefon și nu pleacă nicăieri.",
        ),
        Section(
            "Camera",
            "Camera pornește doar la Oglindă, ca să vezi forma gurii. Telefonul calculează pe loc câteva valori despre mișcarea feței și nu le păstrează. Nu salvăm și nu trimitem nicio imagine.",
        ),
        // Adults' takes are kept and children's only with the switch (SessionRun.keepsAudio); RecordingRetentionPolicy
        // keeps 3 per sound for 90 days. The journal is off until switched on (PilotSwitch) and keeps a day's counters
        // only after PilotLog.DETAIL_DAYS.
        Section(
            "Înregistrările și cât se păstrează",
            "La adulți, încercările se salvează. La copii, doar dacă adultul pornește salvarea din Setări. Rămân ultimele 3 încercări la fiecare sunet, cel mult 90 de zile. Jurnalul de exersare pornește doar dacă îl pornești tu, din Setări. Are doar numere, fără voce. Notează fiecare încercare și o ține cel mult 90 de zile. După aceea rămân doar totalurile pe zi.",
        ),
        // VoiceStore (the voice reference and the dots of the takes), TalkerMemory, the quick checks and the drills:
        // numbers per profile, gone with the profile and with "Șterge tot".
        Section(
            "Ce mai ține minte telefonul",
            "Pentru fiecare profil, telefonul ține minte câteva cifre despre voce, ca să asculte mai bine, rezultatele verificărilor rapide și cele mai bune rezultate la exerciții. Sunt doar cifre, fără voce. Se șterg odată cu profilul.",
        ),
        if (withReading) {
            Section(
                "Probele de citire",
                "Păstrăm data, lista citită, timpul și cuvintele marcate de tine. Raportul îl faci și îl trimiți doar tu. Se șterg odată cu profilul.",
            )
        } else {
            null
        },
        Section(
            "Modul adaptiv",
            "Dacă îl pornești, telefonul ține minte, pe fiecare sunet, cât de sigur iese, ca să aleagă exercițiile următoare. Sunt doar cifre, fără voce. Se șterg odată cu profilul.",
        ),
        // exportJSON keeps includeAudio and includeName off, and the file is ProfileExport.FILE_NAME. The journal's
        // summary goes along only while the journal is on.
        Section(
            "Fișierul pentru logoped",
            "Îl faci tu, din Setări, și îl trimiți cui vrei. Conține profilul, progresul la fiecare sunet, lecțiile, încercările, notițele, textul recunoscut din discursuri și, dacă jurnalul e pornit, un rezumat al lui. Nu conține vocea și nici numele. Fonia nu îl trimite singură. La fel e și cu raportul PDF pentru logoped" +
                if (withReading) " și cu raportul de citire." else ".",
        ),
        // InstallCount: one random UUID and "android", a day after the first profile, only while the switch in Settings
        // is on, never in debug builds. The table takes inserts only. Wiping clears its keys with the settings file.
        Section(
            "Numărul de instalări",
            "La o zi după primul profil, Fonia trimite o dată un cod făcut la întâmplare pe telefon și cuvântul „android”, ca să știm cam câte instalări sunt. Codul nu e legat de niciun profil și nu spune nimic despre tine sau despre exerciții. Ajunge într-o bază de date Supabase din Frankfurt. Ca la orice conexiune, serverul vede atunci adresa IP a telefonului. Jurnalele tehnice ale serverului o țin câteva zile, iar în tabel nu o păstrăm. Până pleacă, îl poți opri din Setări, la „Numără instalarea”. „Șterge tot” scoate codul și alegerea de pe telefon, iar Fonia o ia de la capăt ca o instalare nouă.",
        ),
        // AppModel.wipeEverything.
        Section(
            "Ștergerea",
            "La Setări, „Șterge tot” scoate de pe telefon profilurile, încercările, înregistrările, jurnalul și alegerile din Setări. Nu se pot recupera. Poți șterge și un singur profil.",
        ),
        Section(
            "Copiii și rolul adultului",
            "Copiii exersează împreună cu un adult. În România, vârsta de consimțământ digital este 16 ani, așa că setările, înregistrările copiilor și fișierul pentru logoped rămân la adult. Fonia nu cere date de la copii.",
        ),
        Section("Contact", contact),
    )

    val contact: String
        get() = "${LegalInfo.OPERATOR_NAME}, ${LegalInfo.CONTACT_EMAIL}. Politica completă: ${LegalInfo.PRIVACY_URL}."
}

/** "Mulțumiri", at the end of the privacy text: what the app is built from, with the licences that ask to be named. */
object Credits {
    val lines: List<String> = listOf(
        // The libraries are the Android ones: WhisperKit and GRDB are not in this app.
        "Fonturi: Baloo 2 (Ek Type, OFL) și Allo (OFL). Imagini: Fluent Emoji (Microsoft, MIT) și Noto Emoji (Google, OFL și Apache 2.0). Iconițe: Phosphor (MIT).",
        // tools/sfx/REPORT.md: ElevenLabs generations and sounds rendered from earshot's bank.
        "Efecte sonore: generate cu ElevenLabs și sintetizate pentru Fonia.",
        // facecamera: LiteRT runs MediaPipe's Face Landmarker models (the Tasks library is not used); audio: Oboe.
        "Camera: CameraX (Apache 2.0). Fața: modelele Face Landmarker din MediaPipe (Google, Apache 2.0), rulate cu LiteRT (Google, Apache 2.0). Sunetul: Oboe (Google, Apache 2.0) și Media3 (Apache 2.0).",
        // mouth3d/src/main/resources/fonia/perioral/CREDITS.txt: CC BY 3.0 asks for the author, the licence and the changes.
        "Gura 3D: scanarea „Infinite” de Lee Perry-Smith / Infinite-Realities (CC BY 3.0, triplegangers.com). Fonia a păstrat partea de jos a feței, a refăcut interiorul gurii și a pregătit buzele și bărbia pentru vorbire. Dinții: după MakeHuman (CC0).",
        "Recunoaștere vocală: Whisper și whisper.cpp (MIT). Date: SQLite (domeniu public) și AndroidX SQLite (Apache 2.0).",
        // CC BY 4.0 asks for the authors, the licence, the source and whether it was changed (phones/MODELS.md).
        "Ascultare pe sunete: ZIPA-small CR-CTC (© autorii ZIPA, Jian Zhu și alții, CC BY 4.0, huggingface.co/anyspeech/zipa-small-crctc-500k, folosit nemodificat) și ONNX Runtime (Microsoft, MIT).",
    )
}
