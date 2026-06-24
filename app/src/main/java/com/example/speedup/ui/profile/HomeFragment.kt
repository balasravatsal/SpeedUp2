package com.example.speedup.ui.profile

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.speedup.R
import com.example.speedup.databinding.FragmentHomeBinding
import com.example.speedup.service.FloatingWidgetService

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ProfileViewModel by activityViewModels()
    private lateinit var repository: com.example.speedup.data.repository.ProfileRepository

    private var activeTabId = R.id.tab_personal

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = com.example.speedup.data.repository.ProfileRepository(requireContext())

        // Bind Profile view model details
        viewModel.profile.observe(viewLifecycleOwner) { profile ->
            binding.profileName.text = profile.fullName.ifEmpty { "User Profile" }
            binding.profileTitle.text = profile.title.ifEmpty { "Job Title" }
            binding.profileAvatarText.text = profile.avatar
            binding.profileCompletionProgress.progress = profile.completion
            binding.profileCompletionText.text = "${profile.completion}%"
        }

        // Setup Tab switching
        setupTabClick(binding.tabPersonal, R.id.tab_personal, PersonalTabFragment())
        setupTabClick(binding.tabExperience, R.id.tab_experience, ExperienceTabFragment())
        setupTabClick(binding.tabEducation, R.id.tab_education, EducationTabFragment())
        setupTabClick(binding.tabSkills, R.id.tab_skills, SkillsTabFragment())
        setupTabClick(binding.tabResume, R.id.tab_resume, ResumeTabFragment())

        // Default tab selection
        selectTab(activeTabId, PersonalTabFragment())

        // FAB: Start floating service and simulate job detection
        binding.fabDetect.setOnClickListener {
            checkOverlayAndStartService()
        }
        
        binding.btnSettings.setOnClickListener {
            Toast.makeText(context, "Settings screen is coming in v2", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupTabClick(textView: TextView, id: Int, fragment: Fragment) {
        textView.setOnClickListener {
            if (activeTabId != id) {
                selectTab(id, fragment)
            }
        }
    }

    private fun selectTab(id: Int, fragment: Fragment) {
        // Clear old tab backgrounds
        resetTabStyle(binding.tabPersonal)
        resetTabStyle(binding.tabExperience)
        resetTabStyle(binding.tabEducation)
        resetTabStyle(binding.tabSkills)
        resetTabStyle(binding.tabResume)

        // Set active style
        val activeView = when (id) {
            R.id.tab_personal -> binding.tabPersonal
            R.id.tab_experience -> binding.tabExperience
            R.id.tab_education -> binding.tabEducation
            R.id.tab_skills -> binding.tabSkills
            R.id.tab_resume -> binding.tabResume
            else -> binding.tabPersonal
        }
        
        activeView.setBackgroundResource(R.drawable.bg_glass_card)
        activeView.setTextColor(resources.getColor(R.color.primary_purple, null))

        activeTabId = id

        // Swap child fragment
        childFragmentManager.beginTransaction()
            .replace(R.id.tab_content_container, fragment)
            .commit()
    }

    private fun resetTabStyle(textView: TextView) {
        textView.background = null
        textView.setTextColor(resources.getColor(R.color.text_muted, null))
    }

    private fun checkOverlayAndStartService() {
        val context = requireContext()
        if (!repository.hasMinimumProfile()) {
            Toast.makeText(context, "Complete your profile first (name, email, phone)", Toast.LENGTH_LONG).show()
            return
        }
        if (Settings.canDrawOverlays(context)) {
            val intent = Intent(context, FloatingWidgetService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Toast.makeText(context, "Floating widget active — tap it on any job page to analyze & fill", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "Please enable 'Display over other apps' permission first", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            startActivity(intent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
