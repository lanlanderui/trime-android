/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.osfans.trime.R
import com.osfans.trime.databinding.FragmentMainBinding
import com.osfans.trime.databinding.ItemSettingsDestinationBinding
import com.osfans.trime.util.navigateWithAnim

class MainFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()

    private var _binding: FragmentMainBinding? = null
    private val binding get() = checkNotNull(_binding)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FragmentMainBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        val dataDestinations =
            listOf(
                Destination(
                    R.string.schemata,
                    R.string.settings_schemata_summary,
                    R.drawable.ic_round_view_list_24,
                    NavigationRoute.SchemaList,
                ),
                Destination(
                    R.string.user_dictionary,
                    R.string.settings_user_dictionary_summary,
                    R.drawable.ic_baseline_book_24,
                    NavigationRoute.UserDict,
                ),
                Destination(
                    R.string.profile,
                    R.string.settings_profile_summary,
                    R.drawable.ic_baseline_snippet_folder_24,
                    NavigationRoute.Profile,
                ),
            )
        val experienceDestinations =
            listOf(
                Destination(
                    R.string.general,
                    R.string.settings_general_summary,
                    R.drawable.ic_baseline_tune_24,
                    NavigationRoute.General,
                ),
                Destination(
                    R.string.virtual_keyboard,
                    R.string.settings_virtual_keyboard_summary,
                    R.drawable.ic_baseline_keyboard_24,
                    NavigationRoute.VirtualKeyboard,
                ),
                Destination(
                    R.string.candidates_window,
                    R.string.settings_candidates_window_summary,
                    R.drawable.ic_baseline_list_alt_24,
                    NavigationRoute.CandidatesWindow,
                ),
                Destination(
                    R.string.theme,
                    R.string.settings_theme_summary,
                    R.drawable.ic_baseline_color_lens_24,
                    NavigationRoute.Theme,
                ),
                Destination(
                    R.string.clipboard,
                    R.string.settings_clipboard_summary,
                    R.drawable.ic_clipboard_24,
                    NavigationRoute.Clipboard,
                ),
                Destination(
                    R.string.advanced,
                    R.string.settings_advanced_summary,
                    R.drawable.ic_baseline_more_horiz_24,
                    NavigationRoute.Advanced,
                ),
            )

        addDestinations(binding.dataSettingsContainer, dataDestinations)
        addDestinations(binding.experienceSettingsContainer, experienceDestinations)
    }

    override fun onStart() {
        super.onStart()
        viewModel.enableTopOptionsMenu()
    }

    override fun onStop() {
        viewModel.disableTopOptionsMenu()
        super.onStop()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun addDestinations(
        container: LinearLayout,
        destinations: List<Destination>,
    ) {
        destinations.forEachIndexed { index, destination ->
            val item =
                ItemSettingsDestinationBinding.inflate(layoutInflater, container, false).apply {
                    icon.setImageResource(destination.icon)
                    title.setText(destination.title)
                    summary.setText(destination.summary)
                    divider.isVisible = index != destinations.lastIndex
                    root.setOnClickListener {
                        findNavController().navigateWithAnim(destination.route)
                    }
                }
            container.addView(item.root)
        }
    }

    private data class Destination(
        @StringRes val title: Int,
        @StringRes val summary: Int,
        @DrawableRes val icon: Int,
        val route: NavigationRoute,
    )
}
