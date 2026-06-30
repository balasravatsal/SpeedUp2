package com.example.speedup.ui.onboarding

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.speedup.MainActivity
import com.example.speedup.R
import com.example.speedup.data.model.Education
import com.example.speedup.data.model.UserProfile
import com.example.speedup.data.model.WorkExperience
import com.example.speedup.databinding.FragmentProfileSetupBinding
import com.example.speedup.ui.profile.ProfileViewModel
import java.util.UUID

class ProfileSetupFragment : Fragment() {

    private var _binding: FragmentProfileSetupBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ProfileViewModel by activityViewModels()

    private var currentStep = 0
    private val totalSteps = 5
    private val setupSkills = mutableListOf<String>()
    private val setupExperiences = mutableListOf<WorkExperience>()
    private val setupEducation = mutableListOf<Education>()
    private var resumeUri: String = ""
    private var resumeFileName: String = ""

    private val resumePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                resumeUri = uri.toString()
                resumeFileName = uri.lastPathSegment ?: "resume.pdf"
                binding.tvResumeStatus.text = resumeFileName
            }
        }
    }

    private val stepViews by lazy {
        listOf(
            binding.stepPersonal,
            binding.stepExperience,
            binding.stepEducation,
            binding.stepSkills,
            binding.stepResume
        )
    }

    private val stepTitles = listOf(
        "Your personal info" to "We'll use this to compare you against job requirements",
        "Work experience" to "Add your most recent role (optional but recommended)",
        "Education" to "Add your highest qualification",
        "Skills" to "Add skills to match against job descriptions",
        "Resume" to "Upload your resume for future auto-attach"
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnAddSkill.setOnClickListener { addSkillFromInput() }
        binding.btnUploadResume.setOnClickListener { pickResume() }
        binding.btnSetupBack.setOnClickListener { goBack() }
        binding.btnSetupNext.setOnClickListener { goNext() }

        updateStepUi()
    }

    private fun goBack() {
        if (currentStep > 0) {
            currentStep--
            updateStepUi()
        }
    }

    private fun goNext() {
        if (!validateCurrentStep()) return

        saveCurrentStepData()

        if (currentStep < totalSteps - 1) {
            currentStep++
            updateStepUi()
        } else {
            finishSetup()
        }
    }

    private fun validateCurrentStep(): Boolean {
        return when (currentStep) {
            0 -> {
                val firstName = binding.etFirstName.text.toString().trim()
                val email = binding.etEmail.text.toString().trim()
                val phone = binding.etPhone.text.toString().trim()
                when {
                    firstName.isEmpty() -> {
                        binding.etFirstName.error = "Required"
                        false
                    }
                    email.isEmpty() || !email.contains("@") -> {
                        binding.etEmail.error = "Valid email required"
                        false
                    }
                    phone.isEmpty() -> {
                        binding.etPhone.error = "Required"
                        false
                    }
                    else -> true
                }
            }
            else -> true
        }
    }

    private fun saveCurrentStepData() {
        when (currentStep) {
            0 -> savePersonalInfo()
            1 -> saveExperienceStep()
            2 -> saveEducationStep()
            3 -> saveSkillsStep()
            4 -> saveResumeStep()
        }
    }

    private fun savePersonalInfo() {
        val profile = UserProfile(
            firstName = binding.etFirstName.text.toString().trim(),
            lastName = binding.etLastName.text.toString().trim(),
            email = binding.etEmail.text.toString().trim(),
            phone = binding.etPhone.text.toString().trim(),
            countryCode = binding.etCountryCode.text.toString().trim().ifEmpty { "+1" },
            location = binding.etLocation.text.toString().trim(),
            title = binding.etTitle.text.toString().trim(),
            linkedIn = binding.etLinkedin.text.toString().trim(),
            skills = setupSkills.toList(),
            resumeFileName = resumeFileName,
            resumeUri = resumeUri
        )
        viewModel.saveProfile(profile)
    }

    private fun saveExperienceStep() {
        val title = binding.etExpTitle.text.toString().trim()
        val company = binding.etExpCompany.text.toString().trim()
        val duration = binding.etExpDuration.text.toString().trim()

        if (title.isNotEmpty() && company.isNotEmpty()) {
            setupExperiences.add(
                WorkExperience(
                    UUID.randomUUID().toString(),
                    title,
                    company,
                    duration.ifEmpty { "1 year" },
                    skillsUsed = setupSkills.toList()
                )
            )
            binding.etExpTitle.text?.clear()
            binding.etExpCompany.text?.clear()
            binding.etExpDuration.text?.clear()
        }

        if (setupExperiences.isNotEmpty()) {
            viewModel.saveExperiences(setupExperiences)
        }
    }

    private fun saveEducationStep() {
        val degree = binding.etEduDegree.text.toString().trim()
        val school = binding.etEduSchool.text.toString().trim()
        val year = binding.etEduYear.text.toString().trim()

        if (degree.isNotEmpty() && school.isNotEmpty()) {
            setupEducation.add(
                Education(UUID.randomUUID().toString(), degree, school, year.ifEmpty { "2024" })
            )
        }

        if (setupEducation.isNotEmpty()) {
            viewModel.saveEducation(setupEducation)
        }
    }

    private fun saveSkillsStep() {
        addSkillFromInput()
        val current = viewModel.profile.value ?: UserProfile()
        viewModel.saveProfile(current.copy(skills = setupSkills.toList()))
    }

    private fun saveResumeStep() {
        if (resumeFileName.isNotEmpty()) {
            val current = viewModel.profile.value ?: UserProfile()
            viewModel.saveProfile(
                current.copy(resumeFileName = resumeFileName, resumeUri = resumeUri)
            )
        }
    }

    private fun finishSetup() {
        viewModel.markProfileSetupCompleted()
        (activity as? MainActivity)?.showPermissionsScreen()
    }

    private fun addSkillFromInput() {
        val skill = binding.etSkillInput.text.toString().trim()
        if (skill.isNotEmpty() && !setupSkills.contains(skill)) {
            setupSkills.add(skill)
            binding.etSkillInput.text?.clear()
            refreshSkillsUi()
        }
    }

    private fun refreshSkillsUi() {
        binding.setupSkillsFlow.removeAllViews()
        val inflater = LayoutInflater.from(context)
        for (skill in setupSkills) {
            val tagView = inflater.inflate(R.layout.item_skill_tag, binding.setupSkillsFlow, false) as TextView
            tagView.text = skill
            binding.setupSkillsFlow.addView(tagView)
        }
    }

    private fun pickResume() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        resumePicker.launch(intent)
    }

    private fun updateStepUi() {
        stepViews.forEachIndexed { index, view ->
            view.visibility = if (index == currentStep) View.VISIBLE else View.GONE
        }

        binding.setupStepLabel.text = "Step ${currentStep + 1} of $totalSteps"
        binding.setupTitle.text = stepTitles[currentStep].first
        binding.setupSubtitle.text = stepTitles[currentStep].second
        binding.btnSetupBack.visibility = if (currentStep > 0) View.VISIBLE else View.GONE
        binding.btnSetupNext.text = if (currentStep == totalSteps - 1) "Finish" else "Continue"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
