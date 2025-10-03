package com.webasto.heater

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.webasto.heater.ui.theme.ControlFragment
import com.webasto.heater.ui.theme.SettingsFragment
import com.webasto.heater.ui.theme.FuelFragment

class ViewPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {

    private val fragments = listOf(
        ControlFragment(),
        SettingsFragment(),
        FuelFragment()
    )

    override fun getItemCount(): Int = fragments.size

    override fun createFragment(position: Int): Fragment = fragments[position]

    fun updateAllFragments(data: Map<String, Any>) {
        fragments.forEach { fragment ->
            try {
                val method = fragment.javaClass.getMethod("updateData", Map::class.java)
                method.invoke(fragment, data)
            } catch (e: Exception) {
                // Игнорируем ошибки
            }
        }
    }

    // Новый метод для загрузки настроек только в SettingsFragment
    fun loadSettingsToFragment(data: Map<String, Any>) {
        fragments.forEach { fragment ->
            if (fragment is SettingsFragment) {
                fragment.loadSettingsFromData(data)
            } else {
                // Для других фрагментов используем обычное обновление
                try {
                    val method = fragment.javaClass.getMethod("updateData", Map::class.java)
                    method.invoke(fragment, data)
                } catch (e: Exception) {
                    // Игнорируем ошибки
                }
            }
        }
    }
}