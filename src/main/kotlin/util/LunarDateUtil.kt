package util

import com.github.heqiao2010.lunar.LunarCalendar
import com.github.heqiao2010.lunar.LunarCalendar.solar2Lunar
import java.time.OffsetDateTime
import java.util.Calendar

/**
 * 农历日期工具类
 */
object LunarDateUtil {

    /**
     * 获取农历日期字符串
     * @param offsetDateTime 公历日期时间
     * @return 农历日期字符串，格式如：甲辰年（龙）十二月廿七
     */
    fun getLunarDateString(offsetDateTime: OffsetDateTime): String {
        val calendar = Calendar.getInstance()
        calendar.set(
            offsetDateTime.year,
            offsetDateTime.monthValue - 1,
            offsetDateTime.dayOfMonth
        )
        val lunar = solar2Lunar(calendar)
        return lunar.toString()
    }

    /**
     * 获取农历信息（包含年份和生肖）
     * @param offsetDateTime 公历日期时间
     * @return 农历信息字符串
     */
    fun getLunarInfo(offsetDateTime: OffsetDateTime): String {
        val calendar = Calendar.getInstance()
        calendar.set(
            offsetDateTime.year,
            offsetDateTime.monthValue - 1,
            offsetDateTime.dayOfMonth
        )
        val lunar = solar2Lunar(calendar)
        return lunar.getFullLunarName()
    }

    /**
     * 获取当前日期的节假日信息
     * @param offsetDateTime 公历日期时间
     * @return 节假日名称，如果不是节假日则返回null
     */
    fun getHoliday(offsetDateTime: OffsetDateTime): String? {
        val month = offsetDateTime.monthValue
        val day = offsetDateTime.dayOfMonth
        val lunar = getLunarCalendar(offsetDateTime)

        // 公历节日
        when (month) {
            1 -> {
                if (day == 1) return "元旦"
            }
            2 -> {
                if (day == 14) return "情人节"
            }
            3 -> {
                if (day == 8) return "妇女节"
                if (day == 12) return "植树节"
                if (day == 15) return "消费者权益日"
            }
            4 -> {
                if (day == 1) return "愚人节"
                if (day == 4) return "清明节"
            }
            5 -> {
                if (day == 1) return "劳动节"
                if (day == 4) return "青年节"
            }
            6 -> {
                if (day == 1) return "儿童节"
            }
            7 -> {
                if (day == 1) return "建党节"
            }
            8 -> {
                if (day == 1) return "建军节"
            }
            9 -> {
                if (day == 10) return "教师节"
            }
            10 -> {
                if (day == 1) return "国庆节"
            }
            12 -> {
                if (day == 24) return "平安夜"
                if (day == 25) return "圣诞节"
            }
        }

        // 农历节日
        val lunarMonth = lunar.lunarMonth
        val lunarDay = lunar.dayOfLunarMonth

        when (lunarMonth) {
            1 -> {
                if (lunarDay == 1) return "春节"
                if (lunarDay == 15) return "元宵节"
            }
            2 -> {
                if (lunarDay == 2) return "龙抬头"
            }
            5 -> {
                if (lunarDay == 5) return "端午节"
            }
            7 -> {
                if (lunarDay == 7) return "七夕节"
            }
            8 -> {
                if (lunarDay == 15) return "中秋节"
            }
            9 -> {
                if (lunarDay == 9) return "重阳节"
            }
            12 -> {
                if (lunarDay == 8) return "腊八节"
                if (lunarDay == 23) return "小年"  // 北方小年
                if (lunarDay == 24) return "小年"  // 南方小年
            }
        }

        // 二十四节气（简化版，只判断几个主要的）
        // 注意：这里使用简化的判断，实际节气计算比较复杂
        // 可以使用更精确的节气计算库

        return null
    }

    /**
     * 获取LunarCalendar对象
     */
    private fun getLunarCalendar(offsetDateTime: OffsetDateTime): LunarCalendar {
        val calendar = Calendar.getInstance()
        calendar.set(
            offsetDateTime.year,
            offsetDateTime.monthValue - 1,
            offsetDateTime.dayOfMonth
        )
        return solar2Lunar(calendar)
    }

    /**
     * 获取格式化的农历和节假日信息
     * @param offsetDateTime 公历日期时间
     * @return 格式化的字符串，如：农历甲辰年（龙）十二月廿七 春节
     */
    fun getFormattedLunarAndHoliday(offsetDateTime: OffsetDateTime): String {
        val lunarInfo = getLunarInfo(offsetDateTime)
        val holiday = getHoliday(offsetDateTime)

        return if (holiday != null) {
            "$lunarInfo $holiday"
        } else {
            lunarInfo
        }
    }
}
