package com.bod3.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bod3.app.bringup.BringUpCandidate
import com.bod3.app.bringup.BringUpController
import com.bod3.app.bringup.BringUpState
import java.io.File

@Composable
fun BringUpSection(
    state: BringUpState, controller: BringUpController, connected: Boolean,
    onStart: () -> Unit, writeReport: suspend () -> File, writeSetups: suspend () -> File, onImport: () -> Unit,
) {
    val share = rememberShare("application/json", "Read-only ECU bring-up report", "Share bring-up report", writeReport)
    val export = rememberShare("application/json", "Verified ECU setups", "Share verified setup data pack", writeSetups)
    SectionCard("Read-only ECU bring-up") {
        Text("For this manually selected vehicle and system only. Ranked by distinct supporting source records; tied ranks use source ID. Automatic probing stops at the first working setup.", style = MaterialTheme.typography.bodySmall)
        Text("Only exact identification, one live request and DTC read are probed. No clearing, active tests, session changes, security access or coding. Unknown setup fields stay blocked, not guessed.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = state.vehicleInstance, onValueChange = controller::vehicleInstance,
            label = { Text("Unique vehicle instance (garage label or VIN)") }, enabled = !state.running,
            supportingText = { Text("Enter manually. Use different labels for different cars of the same variant. VIN never selects configuration.") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(if (state.verifiedHere) "Verified on vehicle · this connection. Same-ECU identity is rechecked before each guarded operation."
            else "Not verified on this connection. Saved/imported evidence alone cannot unlock live definitions or writes.",
            color = if (state.verifiedHere) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (state.running) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            state.progress?.let { p -> Text("Candidate ${p.index}/${p.total} · ${p.candidateId} · ${p.role?.name ?: "setup"}", style = MaterialTheme.typography.labelSmall) }
            Button(onClick = controller::stop) { Text("Stop bring-up") }
        } else {
            val probeable = state.candidates.any { it.runnable && (state.manualCandidateId == null || state.manualCandidateId == it.id) }
            Button(onClick = onStart, enabled = state.candidates.isNotEmpty() && state.vehicleInstance.isNotBlank() && (!probeable || connected)) {
                Text(if (!probeable) "Report blocked candidates · no ECU traffic" else if (state.manualCandidateId == null) "Try ranked read-only candidates" else "Try selected candidate · read-only")
            }
            if (probeable && !connected) Text("Connect the adapter first. Generic OBD detection need not succeed for an enhanced-only ECU.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { controller.choose(null) }, enabled = state.manualCandidateId != null) { Text("Use automatic ranked order") }
        }
        OutlinedButton(onClick = share, enabled = !state.running && state.report != null) { Text("Share bring-up report") }
        Text("One JSON file includes the candidate list, candidates tried, complete raw adapter replies, decoded probe results, timing and failures. It may contain private identifiers; review before sharing.", style = MaterialTheme.typography.bodySmall)
        state.report?.let { r ->
            Text("Last run: ${r.outcome} · ${r.attempts.size} candidates · ${r.exchanges.size} exchanges · " +
                if (r.logComplete) "complete evidence" else "INCOMPLETE evidence — not verified", style = MaterialTheme.typography.labelSmall)
            r.attempts.forEach { a ->
                Text("${a.candidateId}: ${a.outcome} — ${a.detail}", style = MaterialTheme.typography.bodySmall)
                a.probes.forEach { p -> Text("${p.role}: ${p.request} → ${p.payload?.take(160) ?: "no valid positive reply"} · ${if (p.passed) "PASS" else "FAIL"}", style = MaterialTheme.typography.labelSmall) }
            }
        }
        HorizontalDivider()
        Text("${state.candidates.size} recovered candidates · ${state.candidates.count { it.runnable }} eligible for probing", style = MaterialTheme.typography.titleSmall)
        if (state.candidates.isEmpty()) Text("No exact recovered entry candidates were published for this system. No generic address is substituted.", style = MaterialTheme.typography.bodySmall)
        state.candidates.forEachIndexed { index, candidate ->
            CandidateDetails(candidate, index + 1, state.manualCandidateId == candidate.id, state.running) { controller.choose(candidate.id) }
        }
        HorizontalDivider()
        Text("Saved / transferred setups", style = MaterialTheme.typography.titleSmall)
        if (state.saved.isEmpty()) Text("No setup evidence saved for this exact vehicle path/system yet.", style = MaterialTheme.typography.bodySmall)
        state.saved.forEach { saved ->
            val c = saved.report.workingCandidate
            Text("${saved.report.scope.vehicleInstance} · ${if (saved.transferred) "Transferred evidence — re-verify here" else "Verified on vehicle (saved history, re-verify after reconnect)"} · ${saved.report.finishedAtMs}", style = MaterialTheme.typography.bodySmall)
            val match = c?.let { savedCandidate -> state.candidates.firstOrNull { it.id == savedCandidate.id && it.fingerprint == savedCandidate.fingerprint } }
            TextButton(onClick = { controller.choose(match?.id) }, enabled = !state.running && match != null) { Text(if (match != null) "Select saved setup for read-only retry" else "Definitions changed / not available — cannot apply") }
        }
        OutlinedButton(onClick = export, enabled = !state.running && state.saved.isNotEmpty()) { Text("Export verified setups data pack") }
        OutlinedButton(onClick = onImport, enabled = !state.running) { Text("Import setup data pack…") }
        Text("Transfer carries data and evidence, not write trust or an automatic vehicle choice. Another phone/car must match the pinned definitions and pass read-only bring-up again. Active tests/coding still require the existing Expert mode, confirmations, backup, read-back, rollback and cleanup rules.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CandidateDetails(candidate: BringUpCandidate, rank: Int, selected: Boolean, running: Boolean, onSelect: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$rank. ${candidate.name} · ${candidate.supportCount} supporting source record(s)${if (selected) " · SELECTED" else ""}", style = MaterialTheme.typography.labelLarge)
        val runtime = candidate.runtime
        if (runtime != null) {
            val ecu = runtime.ecu; val link = requireNotNull(ecu.link); val k = link.kLine
            Text("${link.protocol.displayName} · ${link.dialect} · baud ${k?.baud ?: link.baudKbps ?: "protocol-defined"}", style = MaterialTheme.typography.bodySmall)
            Text("Init ${k?.init ?: "CAN (no K-line init)"} · ${ecu.requestId} → ${ecu.responseId} · session ${runtime.session}", style = MaterialTheme.typography.bodySmall)
            if (k != null) Text("P1 ${k.p1MaxMs}, P2 ${k.p2MaxMs}, P2* ${k.p2StarMs}, P3 ${k.p3MinMs}..${k.p3MaxMs}, P4 ${k.p4Ms} ms; init address ${k.initAddress}; pulses ${k.fastLowMs}/${k.fastHighMs} ms; parity ${k.initParity}/${k.dataParity}; slow timings ${k.slowInitTimingsMs}", style = MaterialTheme.typography.labelSmall)
            Text("ID ${runtime.identification.request} · Live ${runtime.live.request} · DTC ${ecu.diagnostics?.faults?.read?.request}", style = MaterialTheme.typography.labelSmall)
        }
        candidate.metadata.forEach { (key, value) -> Text("$key: $value", style = MaterialTheme.typography.labelSmall) }
        Text("Source IDs: ${candidate.sourceRecords.joinToString()}", style = MaterialTheme.typography.labelSmall)
        candidate.reasons.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        OutlinedButton(onClick = onSelect, enabled = !running) { Text(if (candidate.runnable) "Select only this candidate" else "Select for blocked-candidate report") }
    }
}
