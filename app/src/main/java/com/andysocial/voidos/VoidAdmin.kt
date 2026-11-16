package com.andysocial.voidos

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class VoidAdmin : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Policy.applyAll(context)
    }
}
