package com.example.speedup.ui.onboarding

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.example.speedup.MainActivity
import com.example.speedup.R
import com.example.speedup.data.repository.ProfileRepository
import com.example.speedup.databinding.FragmentOnboardingBinding

class OnboardingFragment : Fragment() {

    private var _binding: FragmentOnboardingBinding? = null
    private val binding get() = _binding!!
    private lateinit var repository: ProfileRepository
    private var currentSlide = 0

    private val slides = listOf(
        Slide(
            "Your job search, smarter",
            "See how well you match before you spend time applying",
            R.drawable.ic_zap
        ),
        Slide(
            "Know your fit before you apply",
            "Smart job matching analysis directly on screen",
            R.drawable.ic_target
        ),
        Slide(
            "One tap on any job page",
            "Floating widget with instant fit score and skill breakdown",
            R.drawable.ic_wand
        )
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOnboardingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = ProfileRepository(requireContext())

        updateSlide()

        binding.btnNext.setOnClickListener {
            if (currentSlide < slides.size - 1) {
                currentSlide++
                updateSlide()
            } else {
                completeIntro()
            }
        }

        binding.btnSkip.setOnClickListener {
            completeIntro()
        }
    }

    private fun updateSlide() {
        val slide = slides[currentSlide]
        binding.slideTitle.text = slide.title
        binding.slideSubtitle.text = slide.subtitle
        binding.slideIcon.setImageResource(slide.icon)

        val activeParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(6)).apply {
            setMargins(dpToPx(3), 0, dpToPx(3), 0)
        }
        val inactiveParams = LinearLayout.LayoutParams(dpToPx(6), dpToPx(6)).apply {
            setMargins(dpToPx(3), 0, dpToPx(3), 0)
        }

        binding.dot0.layoutParams = if (currentSlide == 0) activeParams else inactiveParams
        binding.dot0.setBackgroundResource(if (currentSlide == 0) R.drawable.bg_dot_active else R.drawable.bg_dot_inactive)

        binding.dot1.layoutParams = if (currentSlide == 1) activeParams else inactiveParams
        binding.dot1.setBackgroundResource(if (currentSlide == 1) R.drawable.bg_dot_active else R.drawable.bg_dot_inactive)

        binding.dot2.layoutParams = if (currentSlide == 2) activeParams else inactiveParams
        binding.dot2.setBackgroundResource(if (currentSlide == 2) R.drawable.bg_dot_active else R.drawable.bg_dot_inactive)

        binding.btnNext.text = if (currentSlide == slides.size - 1) "Get Started" else "Next"
    }

    private fun completeIntro() {
        repository.setIntroCompleted(true)
        (activity as? MainActivity)?.showProfileSetup()
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private data class Slide(val title: String, val subtitle: String, val icon: Int)
}
