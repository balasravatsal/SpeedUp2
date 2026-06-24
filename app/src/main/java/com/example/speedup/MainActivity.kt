package com.example.speedup

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.example.speedup.data.repository.ProfileRepository
import com.example.speedup.databinding.ActivityMainBinding
import com.example.speedup.ui.onboarding.OnboardingFragment
import com.example.speedup.ui.onboarding.PermissionsFragment
import com.example.speedup.ui.onboarding.ProfileSetupFragment
import com.example.speedup.ui.profile.HomeFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: ProfileRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = ProfileRepository(this)

        if (savedInstanceState == null) {
            routeInitialScreen()
        }
    }

    private fun routeInitialScreen() {
        when {
            repository.isOnboardingCompleted() -> showHomeScreen()
            repository.isProfileSetupCompleted() -> showPermissionsScreen()
            repository.isIntroCompleted() -> showProfileSetup()
            else -> showOnboardingScreen()
        }
    }

    fun showOnboardingScreen() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_container, OnboardingFragment())
            .commit()
    }

    fun showProfileSetup() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_container, ProfileSetupFragment())
            .commit()
    }

    fun showPermissionsScreen() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_container, PermissionsFragment())
            .commit()
    }

    fun showHomeScreen() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_container, HomeFragment())
            .commit()
    }
}
