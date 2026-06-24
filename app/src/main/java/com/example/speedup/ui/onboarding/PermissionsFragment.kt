package com.example.speedup.ui.onboarding

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.speedup.MainActivity
import com.example.speedup.data.repository.ProfileRepository
import com.example.speedup.databinding.FragmentPermissionsBinding
import com.example.speedup.service.SpeedUpAccessibilityService

class PermissionsFragment : Fragment() {

    private var _binding: FragmentPermissionsBinding? = null
    private val binding get() = _binding!!
    private lateinit var repository: ProfileRepository

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPermissionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = ProfileRepository(requireContext())

        binding.btnGrantPermissions.setOnClickListener { requestPermissions() }
        binding.btnSkipPermissions.setOnClickListener { completeSetup() }
    }

    override fun onResume() {
        super.onResume()
        val context = context ?: return
        if (Settings.canDrawOverlays(context) &&
            isAccessibilityServiceEnabled(context, SpeedUpAccessibilityService::class.java)
        ) {
            completeSetup()
        }
    }

    private fun requestPermissions() {
        val context = requireContext()
        if (!Settings.canDrawOverlays(context)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
            )
            return
        }
        if (!isAccessibilityServiceEnabled(context, SpeedUpAccessibilityService::class.java)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        completeSetup()
    }

    private fun completeSetup() {
        repository.setOnboardingCompleted(true)
        (activity as? MainActivity)?.showHomeScreen()
    }

    private fun isAccessibilityServiceEnabled(
        context: Context,
        service: Class<out AccessibilityService>
    ): Boolean {
        val expectedComponentName = "${context.packageName}/${service.name}"
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServicesSetting.split(':').any {
            it.equals(expectedComponentName, ignoreCase = true)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
