/*
 * Copyright (C) 2022 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dimadesu.lifestreamer.ui

import android.view.View
import androidx.databinding.BindingAdapter
import androidx.databinding.InverseBindingAdapter
import androidx.databinding.InverseBindingListener
import com.google.android.material.slider.Slider

object BindingAdapters {
    @BindingAdapter("goneUnless")
    @JvmStatic
    fun goneUnless(view: View, visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.GONE
    }
}

@InverseBindingAdapter(attribute = "android:value")
fun getSliderValue(slider: Slider) = slider.value

@BindingAdapter("android:valueAttrChanged")
fun setSliderListeners(slider: Slider, attrChange: InverseBindingListener) {
    slider.addOnChangeListener { _, _, _ ->
        attrChange.onChange()
    }
}

/**
 * Custom binding adapter that sets slider value, valueFrom, and valueTo together
 * to avoid crashes when the value is outside the default range.
 * 
 * This is needed because cameras with ultra-wide lenses can have zoom ratios < 1.0,
 * but the slider's default valueFrom is often 1.0, causing a crash when the value
 * is set before the range is updated.
 * 
 * Handles both Float and Integer (Number) values.
 */
@BindingAdapter("android:value", "android:valueFrom", "android:valueTo", requireAll = true)
fun setSliderValueWithRange(slider: Slider, value: Number?, valueFrom: Number?, valueTo: Number?) {
    // Skip if any value is null (not yet initialized)
    if (value == null || valueFrom == null || valueTo == null) return
    
    val floatValue = value.toFloat()
    val floatFrom = valueFrom.toFloat()
    val floatTo = valueTo.toFloat()
    
    // Skip if range is invalid
    if (floatFrom >= floatTo) return
    
    // Set range first (before value)
    if (slider.valueFrom != floatFrom) {
        slider.valueFrom = floatFrom
    }
    if (slider.valueTo != floatTo) {
        slider.valueTo = floatTo
    }
    
    // Clamp value to valid range and set it
    val clampedValue = floatValue.coerceIn(floatFrom, floatTo)
    if (slider.value != clampedValue) {
        slider.value = clampedValue
    }
}