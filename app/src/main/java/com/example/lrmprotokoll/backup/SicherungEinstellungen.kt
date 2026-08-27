package com.example.lrmprotokoll.backup

import com.example.lrmprotokoll.data.SettingsManager
import org.json.JSONObject

/**
 * F13 (PROMPT_M10_FUNKTIONEN.md): welche [SettingsManager]-Felder eine Sicherung umfasst.
 *
 * Bewusst NICHT enthalten: [SettingsManager.meterDeviceAddress]/[SettingsManager.meterDeviceName]
 * (BLE-Pairing - nach einer Wiederherstellung neu koppeln, sonst zeigt die App eine gepinnte
 * Adresse ohne echte Bond-Beziehung), Google-Drive-Sitzungszustand
 * ([SettingsManager.googleAccountEmail]/-Name/[SettingsManager.driveFolderId]/
 * [SettingsManager.driveSyncLastMessage]/-LastSuccessAt/-FehlschlaegeInFolge/
 * [SettingsManager.driveOrdnerBlockiert] - Sitzungsdaten, die nach einer Anmeldung ohnehin neu
 * entstehen), [SettingsManager.onboardingCompleted] (harmlos, einmalig erneut zu sehen), die
 * Filter-Laufzeitzustände (ephemer) sowie [SettingsManager.letzteDiagnoseId] (Diagnose-Breadcrumb,
 * keine Einstellung) und [SettingsManager.monitoringWasActive]/-AudioMonitoringWasActive
 * (Dienst-Neustart-Buchführung, nach einer Wiederherstellung ohnehin ungültig).
 */
internal fun buildEinstellungenJson(settings: SettingsManager): JSONObject {
    val json = JSONObject()
    json.put("dbThreshold", settings.dbThreshold)
    json.put("preRollSeconds", settings.preRollSeconds)
    json.put("recordDurationSeconds", settings.recordDurationSeconds)
    json.put("recordWavAudio", settings.recordWavAudio)
    json.put("aiMode", settings.aiMode)
    json.put("aiConfidenceThreshold", settings.aiConfidenceThreshold)
    json.put("audioSampleRate", settings.audioSampleRate)
    json.put("audioTriggerQuelle", settings.audioTriggerQuelle)
    json.put("alarmierungAktiv", settings.alarmierungAktiv)
    json.put("karenzzeitSekunden", settings.karenzzeitSekunden)
    json.put("ntfyAktiv", settings.ntfyAktiv)
    json.put("ntfyServer", settings.ntfyServer)
    json.put("ntfyTopic", settings.ntfyTopic)
    json.put("heartbeatUrl", settings.heartbeatUrl)
    json.put("entwarnungUeberNtfy", settings.entwarnungUeberNtfy)
    json.put("entwarnungUeberMeldung", settings.entwarnungUeberMeldung)
    json.put("alarmTonAktiv", settings.alarmTonAktiv)
    json.put("driveSyncEnabled", settings.driveSyncEnabled)
    json.put("driveFolderName", settings.driveFolderName)
    json.put("driveAggregationSekunden", settings.driveAggregationSekunden)
    json.put("driveWlanOnly", settings.driveWlanOnly)
    json.put("driveUploadWav", settings.driveUploadWav)
    json.put("diagnoseLoggingAktiv", settings.diagnoseLoggingAktiv)
    json.put("isProMode", settings.isProMode)
    json.put("appLanguage", settings.appLanguage)
    json.put("remoteDiagnoseAktiv", settings.remoteDiagnoseAktiv)
    json.put("autoRetentionEnabled", settings.autoRetentionEnabled)
    json.put("autoRetentionDays", settings.autoRetentionDays)
    json.put("quietHoursEnabled", settings.quietHoursEnabled)
    json.put("quietHoursStartHour", settings.quietHoursStartHour)
    json.put("quietHoursStartMinute", settings.quietHoursStartMinute)
    json.put("quietHoursEndHour", settings.quietHoursEndHour)
    json.put("quietHoursEndMinute", settings.quietHoursEndMinute)
    json.put("quietHoursThreshold", settings.quietHoursThreshold)
    return json
}

/**
 * Gegenstück zu [buildEinstellungenJson]: jedes Feld nur übernehmen, wenn es im JSON vorhanden
 * ist ([JSONObject.has]-Wächter) - eine Sicherung aus einer älteren App-Version kann Felder
 * vermissen lassen, das darf die Wiederherstellung der übrigen Felder nicht verhindern.
 */
internal fun wendeEinstellungenAn(json: JSONObject, settings: SettingsManager) {
    if (json.has("dbThreshold")) settings.dbThreshold = json.getDouble("dbThreshold").toFloat()
    if (json.has("preRollSeconds")) settings.preRollSeconds = json.getInt("preRollSeconds")
    if (json.has("recordDurationSeconds")) settings.recordDurationSeconds = json.getInt("recordDurationSeconds")
    if (json.has("recordWavAudio")) settings.recordWavAudio = json.getBoolean("recordWavAudio")
    if (json.has("aiMode")) settings.aiMode = json.getString("aiMode")
    if (json.has("aiConfidenceThreshold")) settings.aiConfidenceThreshold = json.getDouble("aiConfidenceThreshold").toFloat()
    if (json.has("audioSampleRate")) settings.audioSampleRate = json.getInt("audioSampleRate")
    if (json.has("audioTriggerQuelle")) settings.audioTriggerQuelle = json.getString("audioTriggerQuelle")
    if (json.has("alarmierungAktiv")) settings.alarmierungAktiv = json.getBoolean("alarmierungAktiv")
    if (json.has("karenzzeitSekunden")) settings.karenzzeitSekunden = json.getInt("karenzzeitSekunden")
    if (json.has("ntfyAktiv")) settings.ntfyAktiv = json.getBoolean("ntfyAktiv")
    if (json.has("ntfyServer")) settings.ntfyServer = json.getString("ntfyServer")
    if (json.has("ntfyTopic")) settings.ntfyTopic = json.getString("ntfyTopic")
    if (json.has("heartbeatUrl")) settings.heartbeatUrl = json.getString("heartbeatUrl")
    if (json.has("entwarnungUeberNtfy")) settings.entwarnungUeberNtfy = json.getBoolean("entwarnungUeberNtfy")
    if (json.has("entwarnungUeberMeldung")) settings.entwarnungUeberMeldung = json.getBoolean("entwarnungUeberMeldung")
    if (json.has("alarmTonAktiv")) settings.alarmTonAktiv = json.getBoolean("alarmTonAktiv")
    if (json.has("driveSyncEnabled")) settings.driveSyncEnabled = json.getBoolean("driveSyncEnabled")
    if (json.has("driveFolderName")) settings.driveFolderName = json.getString("driveFolderName")
    if (json.has("driveAggregationSekunden")) settings.driveAggregationSekunden = json.getInt("driveAggregationSekunden")
    if (json.has("driveWlanOnly")) settings.driveWlanOnly = json.getBoolean("driveWlanOnly")
    if (json.has("driveUploadWav")) settings.driveUploadWav = json.getBoolean("driveUploadWav")
    if (json.has("diagnoseLoggingAktiv")) settings.diagnoseLoggingAktiv = json.getBoolean("diagnoseLoggingAktiv")
    if (json.has("isProMode")) settings.isProMode = json.getBoolean("isProMode")
    if (json.has("appLanguage")) settings.appLanguage = json.getString("appLanguage")
    if (json.has("remoteDiagnoseAktiv")) settings.remoteDiagnoseAktiv = json.getBoolean("remoteDiagnoseAktiv")
    if (json.has("autoRetentionEnabled")) settings.autoRetentionEnabled = json.getBoolean("autoRetentionEnabled")
    if (json.has("autoRetentionDays")) settings.autoRetentionDays = json.getInt("autoRetentionDays")
    if (json.has("quietHoursEnabled")) settings.quietHoursEnabled = json.getBoolean("quietHoursEnabled")
    if (json.has("quietHoursStartHour")) settings.quietHoursStartHour = json.getInt("quietHoursStartHour")
    if (json.has("quietHoursStartMinute")) settings.quietHoursStartMinute = json.getInt("quietHoursStartMinute")
    if (json.has("quietHoursEndHour")) settings.quietHoursEndHour = json.getInt("quietHoursEndHour")
    if (json.has("quietHoursEndMinute")) settings.quietHoursEndMinute = json.getInt("quietHoursEndMinute")
    if (json.has("quietHoursThreshold")) settings.quietHoursThreshold = json.getDouble("quietHoursThreshold").toFloat()
}
