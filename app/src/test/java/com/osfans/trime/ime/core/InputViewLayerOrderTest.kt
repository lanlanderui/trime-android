/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class InputViewLayerOrderTest :
    BehaviorSpec({
        // InputView lays out the keyboard and the popup layer as siblings, and Android
        // orders sibling drawing by elevation. A floating keyboard raises itself so its
        // drop shadow renders, which used to paint it over the long-press popups.
        Given("a floating keyboard that raises itself for its drop shadow") {
            Then("the popup layer is painted above it") {
                (POPUP_LAYER_ELEVATION_DP > FLOATING_KEYBOARD_ELEVATION_DP) shouldBe true
            }

            Then("the popup layer is not flat, so the sibling re-order still keeps it on top") {
                (POPUP_LAYER_ELEVATION_DP > 0f) shouldBe true
            }
        }
    })
