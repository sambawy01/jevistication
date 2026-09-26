package com.loupeai.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.loupeai.android.HomeSummary

/** The A0 placeholder home: the shared engine's answers on the phone, no model, no data. */
@Composable
fun HomeScreen(summary: HomeSummary?) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Palette.groundMid, Palette.ground, Palette.ground))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Loupe",
                color = Palette.ink,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "Android preview. The shared engine is running on this phone.",
                color = Palette.inkSoft,
                fontSize = 15.sp,
            )
            if (summary == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Palette.cyan)
                }
            } else {
                Content(summary)
            }
        }
    }
}

@Composable
private fun Content(summary: HomeSummary) {
    NeonCard {
        Caption("Judgment templates")
        Text(
            "${summary.templateCount} templates in ${summary.categoryCount} categories",
            color = Palette.ink,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
        for (title in summary.sampleTemplates) {
            Text("· $title", color = Palette.inkSoft, fontSize = 14.sp)
        }
    }
    NeonCard {
        Caption("Check a link")
        for (link in summary.links) {
            val (text, tint) = Palette.level(link.level)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    link.levelTitle,
                    color = text,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .background(tint, RoundedCornerShape(50))
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
            Text(link.shown, color = Palette.ink, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            link.reasons.firstOrNull()?.let { Text(it, color = Palette.inkSoft, fontSize = 13.sp) }
            Spacer(Modifier.height(4.dp))
        }
    }
    NeonCard {
        Caption("Registrable domains")
        for (row in summary.domains) {
            Text(
                "${row.host}  →  ${row.registrable ?: "none"}",
                color = Palette.ink,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Text("Public Suffix List ${summary.pslVersion}", color = Palette.inkSoft, fontSize = 12.sp)
    }
    NeonCard {
        Caption("The Loupe Decision Model")
        Text(
            "Not on this phone yet. It arrives as a download you agree to, and it is never part of the app.",
            color = Palette.inkSoft,
            fontSize = 14.sp,
        )
    }
}

/** Theme.swift `CardStyle`: a cardHigh-to-card gradient, a thin blue border, radius 16. */
@Composable
private fun NeonCard(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Effects.RADIUS_DP.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Palette.cardHigh, Palette.card)), shape)
            .border(1.dp, Palette.border, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

/** Theme.swift `Caption`: quiet mono small caps. */
@Composable
private fun Caption(text: String) {
    Text(
        text.uppercase(),
        color = Palette.inkSoft,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp,
    )
}
