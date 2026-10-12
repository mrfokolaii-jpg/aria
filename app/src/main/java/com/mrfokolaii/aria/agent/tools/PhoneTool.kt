package com.mrfokolaii.aria.agent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class PhoneTool(private val context: Context) : AgentTool {
    override val name = "phone_call"
    override val description = "Make phone calls, search contacts by name, open dialer/contacts"
    override val category = ToolCategory.SYSTEM

    override suspend fun execute(params: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val action = params["action"]?.toString()?.lowercase() ?: "call"
        val phoneNumber = params["phone_number"]?.toString()?.trim()
        val contactName = params["contact_name"]?.toString()?.trim()

        try {
            when {
                action == "contacts" || action == "open_contacts" -> {
                    if (!contactName.isNullOrBlank()) {
                        searchAndDial(contactName)
                    } else {
                        val intent = Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                        ToolResult(true, mapOf("status" to "contacts_opened", "message" to "لیست مخاطبین باز شد"))
                    }
                }
                action == "search" || action == "call_name" -> {
                    val name = contactName ?: params["query"]?.toString()?.trim()
                    if (name.isNullOrBlank()) {
                        ToolResult(false, null, "اسم مخاطب مشخص نیست")
                    } else {
                        searchAndDial(name)
                    }
                }
                action == "dialer" || action == "open_dialer" -> {
                    val intent = if (!phoneNumber.isNullOrBlank()) {
                        Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber"))
                    } else {
                        Intent(Intent.ACTION_DIAL)
                    }.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                    context.startActivity(intent)
                    ToolResult(true, mapOf("status" to "dialer_opened", "message" to "شماره‌گیر باز شد"))
                }
                !phoneNumber.isNullOrBlank() -> {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    ToolResult(true, mapOf("status" to "dialing", "number" to phoneNumber, "message" to "در حال شماره‌گیری $phoneNumber"))
                }
                !contactName.isNullOrBlank() -> searchAndDial(contactName)
                else -> {
                    val intent = Intent(Intent.ACTION_DIAL).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                    context.startActivity(intent)
                    ToolResult(true, mapOf("status" to "dialer_opened", "message" to "شماره‌گیر باز شد"))
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Phone permission denied", e)
            ToolResult(false, null, "مجوز تماس یا مخاطبین لازم است")
        } catch (e: Exception) {
            Log.e(TAG, "Call failed", e)
            ToolResult(false, null, "خطا: ${e.message}")
        }
    }

    private fun searchAndDial(name: String): ToolResult {
        val matches = findContacts(name)
        if (matches.isEmpty()) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
            return ToolResult(
                true,
                mapOf(
                    "status" to "not_found",
                    "query" to name,
                    "message" to "مخاطبی با نام «$name» پیدا نشد. لیست مخاطبین باز شد."
                )
            )
        }
        val first = matches.first()
        val number = first.second
        val display = first.first
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        val extra = if (matches.size > 1) " (${matches.size} مورد پیدا شد، اولی انتخاب شد)" else ""
        return ToolResult(
            true,
            mapOf(
                "status" to "dialing",
                "name" to display,
                "number" to number,
                "matches" to matches.size,
                "message" to "تماس با $display$extra — شماره $number"
            )
        )
    }

    fun findContacts(query: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val args = arrayOf("%$query%")
            context.contentResolver.query(uri, projection, selection, args, null)?.use { c ->
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext() && out.size < 8) {
                    val n = if (nameIdx >= 0) c.getString(nameIdx) ?: continue else continue
                    val p = if (numIdx >= 0) c.getString(numIdx)?.replace(" ", "")?.replace("-", "") else null
                    if (!p.isNullOrBlank()) out.add(n to p)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CONTACTS not granted")
        } catch (e: Exception) {
            Log.e(TAG, "findContacts", e)
        }
        return out
    }

    override suspend fun healthCheck(): HealthStatus =
        HealthStatus(true, 0, "Phone tool ready")

    override fun getParamsSchema(): JSONObject = JSONObject()
        .put("action", "contacts|search|dialer|call")
        .put("phone_number", "string?")
        .put("contact_name", "string?")

    companion object {
        private const val TAG = "PhoneTool"
    }
}
