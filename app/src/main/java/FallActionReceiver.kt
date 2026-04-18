// FallActionReceiver.kt
package com.example.watchsepawv2.presentation

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class FallActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_OK = "com.example.watchsepawv2.FALL_OK"
        const val ACTION_NOT_OK = "com.example.watchsepawv2.FALL_NOT_OK"
        const val NOTIF_ID = 999
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pref = MyPreferenceData(context)

        val fallStatus = when (action) {
            ACTION_OK -> 1
            ACTION_NOT_OK -> 2
            else -> return
        }
        pref.setFallStatus(fallStatus)

//        SingleLocationHelper.getSingleLocation(context) { loc ->
//            // ทำเครื่องหมายว่า session ปัจจุบันถูกตอบแล้ว
//            val p = context.getSharedPreferences("fall_prefs", Context.MODE_PRIVATE)
//            val cur = p.getLong("current_session_id", -2L)
//            p.edit().putLong("handled_session_id", cur).apply()
//
//            // ส่งไปหลังบ้าน (ตามฟอร์แมตเดียวกับ HelpActivity)
//            val client = OkHttpClient()
//            val url = "https://afe-project-production.up.railway.app/api/sentFall"
//            val jsonBody = """
//        {
//            "users_id": "${pref.getUserId()}",
//            "takecare_id": "${pref.getTakecareId()}",
//            "x_axis": "${pref.getXAxis()}",
//            "y_axis": "${pref.getYAxis()}",
//            "z_axis": "${pref.getZAxis()}",
//            "fall_status": "$fallStatus",
//            "latitude": "${standbymain.curLat}",
//            "longitude": "${standbymain.curLong}"
//        }
//    """.trimIndent().toRequestBody()
//
//            val request = Request.Builder()
//                .url(url)
//                .put(jsonBody)
//                .addHeader("Content-Type", "application/json")
//                .build()
//
//            Thread {
//                try {
//                    client.newCall(request).execute().use { resp ->
//                        Log.d("FALL_API", "Action $action sent: ${resp.code}")
//                    }
//                } catch (e: Exception) {
//                    Log.d("FALL_API", "Action $action error: ${e.message}")
//                }
//            }.start()
//        }
        // ทำเครื่องหมายว่า session ปัจจุบันถูกตอบแล้ว
        val p = context.getSharedPreferences("fall_prefs", Context.MODE_PRIVATE)
        val cur = p.getLong("current_session_id", -2L)
        p.edit().putLong("handled_session_id", cur).apply()

        // ส่งไปหลังบ้าน (ตามฟอร์แมตเดียวกับ HelpActivity)
        val client = OkHttpClient()
        val url = "https://afe-project-production.up.railway.app/api/sentFall"
        val jsonBody = """
        {
            "users_id": "${pref.getUserId()}",
            "takecare_id": "${pref.getTakecareId()}",
            "x_axis": "${pref.getXAxis()}",
            "y_axis": "${pref.getYAxis()}",
            "z_axis": "${pref.getZAxis()}",
            "fall_status": "$fallStatus",
            "latitude": "${standbymain.curLat}",
            "longitude": "${standbymain.curLong}"
        }
    """.trimIndent().toRequestBody()

        val request = Request.Builder()
            .url(url)
            .put(jsonBody)
            .addHeader("Content-Type", "application/json")
            .build()

        Thread {
            try {
                client.newCall(request).execute().use { resp ->
                    Log.d("FALL_API", "Action $action sent: ${resp.code}")
                }
            } catch (e: Exception) {
                Log.d("FALL_API", "Action $action error: ${e.message}")
            }
        }.start()

        // ปิดแจ้งเตือน
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NOTIF_ID)
    }

}
