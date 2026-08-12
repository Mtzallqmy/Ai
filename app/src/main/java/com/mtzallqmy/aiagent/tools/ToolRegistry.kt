package com.mtzallqmy.aiagent.tools

data class Capability(
    val id: String,
    val name: String,
    val description: String,
    val isAvailable: Boolean
)

object ToolRegistry {
    fun getCapabilities(): List<Capability> {
        return listOf(
            Capability("device.accessibility", "وصول لتسهيلات الاستخدام", "التفاعل مع عناصر الشاشة والنقر التلقائي", true),
            Capability("browser.engine", "محرك المتصفح الذكي", "تصفح الويب واستخراج البيانات والنصوص", true),
            Capability("terminal.shell", "الطرفية المحلية والبيئة", "تنفيذ الأوامر والسكربتات الآمنة", true),
            Capability("filesystem.io", "إدارة الملفات والمجلدات", "قراءة وكتابة وتخزين المستندات", true),
            Capability("mcp.server", "بروتوكول السياق النموذجي", "التكامل مع خوادم MCP الخارجية", true)
        )
    }
}
