package com.taxidriver.launcher.auto

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator

/**
 * Android Auto entry point. Navigation is intentionally disabled until a compliant
 * route planner, maneuver feed, and car surface renderer are integrated.
 */
class TaxiDriverCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen =
            object : Screen(carContext) {
                override fun onGetTemplate(): Template {
                    val pane = Pane.Builder()
                        .addRow(
                            Row.Builder()
                                .setTitle("Taxi Driver Launcher")
                                .addText("Android Auto connection is ready.")
                                .build(),
                        )
                        .addRow(
                            Row.Builder()
                                .setTitle("Navigation")
                                .addText("Map rendering and turn-by-turn guidance are not enabled yet.")
                                .build(),
                        )
                        .build()
                    return PaneTemplate.Builder(pane)
                        .setTitle("Taxi Driver")
                        .build()
                }
            }
    }
}
