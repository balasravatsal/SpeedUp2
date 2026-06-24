package com.example.speedup.ui.profile

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.speedup.R
import com.example.speedup.databinding.FragmentPersonalBinding

class PersonalTabFragment : Fragment() {

    private var _binding: FragmentPersonalBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ProfileViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPersonalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.profile.observe(viewLifecycleOwner) { profile ->
            binding.tvName.text = profile.fullName.ifEmpty { "Add name" }
            binding.tvEmail.text = profile.email.ifEmpty { "Add email" }
            binding.tvPhone.text = formatPhone(profile)
            binding.tvLocation.text = profile.location.ifEmpty { "Add location" }
            binding.tvWebsite.text = profile.website.ifEmpty { profile.linkedIn.ifEmpty { "Add website link" } }
            binding.tvWebsite.setTextColor(
                resources.getColor(
                    if (profile.website.isEmpty() && profile.linkedIn.isEmpty()) R.color.text_muted else R.color.text_primary,
                    null
                )
            )
        }

        binding.cardPersonalInfo.setOnClickListener { showEditDialog() }
    }

    private fun formatPhone(profile: com.example.speedup.data.model.UserProfile): String {
        if (profile.phone.isEmpty()) return "Add phone number"
        return if (profile.countryCode.isNotBlank() && !profile.phone.startsWith("+")) {
            "${profile.countryCode} ${profile.phone}"
        } else {
            profile.phone
        }
    }

    private fun showEditDialog() {
        val profile = viewModel.profile.value ?: return
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_edit_personal, null)

        dialogView.findViewById<EditText>(R.id.et_edit_first_name).setText(profile.firstName)
        dialogView.findViewById<EditText>(R.id.et_edit_last_name).setText(profile.lastName)
        dialogView.findViewById<EditText>(R.id.et_edit_email).setText(profile.email)
        dialogView.findViewById<EditText>(R.id.et_edit_country_code).setText(profile.countryCode)
        dialogView.findViewById<EditText>(R.id.et_edit_phone).setText(profile.phone)
        dialogView.findViewById<EditText>(R.id.et_edit_location).setText(profile.location)
        dialogView.findViewById<EditText>(R.id.et_edit_title).setText(profile.title)
        dialogView.findViewById<EditText>(R.id.et_edit_website).setText(profile.website)
        dialogView.findViewById<EditText>(R.id.et_edit_linkedin).setText(profile.linkedIn)

        AlertDialog.Builder(requireContext())
            .setTitle("Edit Personal Info")
            .setView(dialogView)
            .setPositiveButton("Save") { dialog, _ ->
                viewModel.updatePersonalInfo(
                    firstName = dialogView.findViewById<EditText>(R.id.et_edit_first_name).text.toString().trim(),
                    lastName = dialogView.findViewById<EditText>(R.id.et_edit_last_name).text.toString().trim(),
                    email = dialogView.findViewById<EditText>(R.id.et_edit_email).text.toString().trim(),
                    phone = dialogView.findViewById<EditText>(R.id.et_edit_phone).text.toString().trim(),
                    countryCode = dialogView.findViewById<EditText>(R.id.et_edit_country_code).text.toString().trim(),
                    location = dialogView.findViewById<EditText>(R.id.et_edit_location).text.toString().trim(),
                    website = dialogView.findViewById<EditText>(R.id.et_edit_website).text.toString().trim(),
                    linkedIn = dialogView.findViewById<EditText>(R.id.et_edit_linkedin).text.toString().trim(),
                    title = dialogView.findViewById<EditText>(R.id.et_edit_title).text.toString().trim()
                )
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
