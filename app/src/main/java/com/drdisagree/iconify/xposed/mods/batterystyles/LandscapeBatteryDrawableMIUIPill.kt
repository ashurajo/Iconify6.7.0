package com.drdisagree.iconify.xposed.mods.batterystyles

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.TypedValue
import androidx.core.graphics.PathParser
import com.drdisagree.iconify.xposed.utils.SettingsLibUtils
import kotlin.math.floor

@SuppressLint("DiscouragedApi")
open class LandscapeBatteryDrawableMIUIPill(private val context: Context, frameColor: Int) :
    BatteryDrawable() {

    // Need to load:
    // 1. perimeter shape
    // 2. fill mask (if smaller than perimeter, this would create a fill that
    //    doesn't touch the walls
    private val perimeterPath = Path()
    private val scaledPerimeter = Path()
    private val errorPerimeterPath = Path()
    private val scaledErrorPerimeter = Path()

    // Fill will cover the whole bounding rect of the fillMask, and be masked by the path
    private val fillMask = Path()
    private val scaledFill = Path()

    // Based off of the mask, the fill will interpolate across this space
    private val fillRect = RectF()

    // Top of this rect changes based on level, 100% == fillRect
    private val levelRect = RectF()
    private val levelPath = Path()

    // Updates the transform of the paths when our bounds change
    private val scaleMatrix = Matrix()
    private val padding = Rect()

    // The net result of fill + perimeter paths
    private val unifiedPath = Path()

    // Bolt path (used while charging)
    private val boltPath = Path()
    private val scaledBolt = Path()

    // Plus sign (used for power save mode)
    private val plusPath = Path()
    private val scaledPlus = Path()

    private var intrinsicHeight: Int
    private var intrinsicWidth: Int

    // To implement hysteresis, keep track of the need to invert the interior icon of the battery
    private var invertFillIcon = false

    // Colors can be configured based on battery level (see res/values/arrays.xml)
    private var colorLevels: IntArray

    private var fillColor: Int = Color.WHITE
    private var boltColor: Int = Color.WHITE
    private var backgroundColor: Int = Color.WHITE

    // updated whenever level changes
    private var levelColor: Int = Color.WHITE

    // Dual tone implies that battery level is a clipped overlay over top of the whole shape
    private var dualTone = false

    private var batteryLevel = 0

    private val invalidateRunnable: () -> Unit = {
        invalidateSelf()
    }

    open var criticalLevel: Int = 5

    var charging = false
        set(value) {
            field = value
            postInvalidate()
        }

    override fun setChargingEnabled(charging: Boolean) {
        this.charging = charging
        postInvalidate()
    }

    var powerSaveEnabled = false
        set(value) {
            field = value
            postInvalidate()
        }

    override fun setPowerSavingEnabled(powerSaveEnabled: Boolean) {
        this.powerSaveEnabled = powerSaveEnabled
        postInvalidate()
    }

    var showPercent = false
        set(value) {
            field = value
            postInvalidate()
        }

    override fun setShowPercentEnabled(showPercent: Boolean) {
        this.showPercent = showPercent
        postInvalidate()
    }

    private val fillColorStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.color = frameColor
        p.alpha = 255
        p.isDither = true
        p.strokeWidth = 1f
        p.style = Paint.Style.STROKE
        p.blendMode = BlendMode.SRC
        p.strokeMiter = 5f
        p.strokeJoin = Paint.Join.ROUND
    }

    private val fillColorStrokeProtection = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.isDither = true
        p.strokeWidth = 5f
        p.style = Paint.Style.STROKE
        p.blendMode = BlendMode.CLEAR
        p.strokeMiter = 5f
        p.strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.color = frameColor
        p.alpha = 255
        p.isDither = true
        p.strokeWidth = 0f
        p.style = Paint.Style.FILL_AND_STROKE
    }

    private val errorPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.color = context.resources.getColorStateList(
            context.resources.getIdentifier(
                "batterymeter_plus_color", "color", context.packageName
            ), context.theme
        ).defaultColor
        p.alpha = 255
        p.isDither = true
        p.strokeWidth = 0f
        p.style = Paint.Style.FILL_AND_STROKE
        p.blendMode = BlendMode.SRC
    }

    // Only used if dualTone is set to true
    private val dualToneBackgroundFill = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.color = frameColor
        p.alpha = 255
        p.isDither = true
        p.strokeWidth = 0f
        p.style = Paint.Style.FILL_AND_STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
        p.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
    }

    init {
        val density = context.resources.displayMetrics.density
        intrinsicHeight = (HEIGHT * density).toInt()
        intrinsicWidth = (WIDTH * density).toInt()

        val res = context.resources
        val levels = res.obtainTypedArray(
            res.getIdentifier(
                "batterymeter_color_levels", "array", context.packageName
            )
        )
        val colors = res.obtainTypedArray(
            res.getIdentifier(
                "batterymeter_color_values", "array", context.packageName
            )
        )
        val n = levels.length()
        colorLevels = IntArray(2 * n)
        for (i in 0 until n) {
            colorLevels[2 * i] = levels.getInt(i, 0)
            if (colors.getType(i) == TypedValue.TYPE_ATTRIBUTE) {
                colorLevels[2 * i + 1] = SettingsLibUtils.getColorAttrDefaultColor(
                    colors.getResourceId(i, 0), context
                )
            } else {
                colorLevels[2 * i + 1] = colors.getColor(i, 0)
            }
        }
        levels.recycle()
        colors.recycle()

        loadPaths()
    }

    override fun draw(c: Canvas) {
        c.saveLayer(null, null)
        unifiedPath.reset()
        levelPath.reset()
        levelRect.set(fillRect)
        val fillFraction = batteryLevel / 100f
        val fillTop = if (batteryLevel >= 95) fillRect.left
        else fillRect.left + (fillRect.width() * (1 - fillFraction))

        levelRect.left = floor(fillTop.toDouble()).toFloat()
        //levelPath.addRect(levelRect, Path.Direction.CCW)
        levelPath.addRoundRect(
            levelRect, floatArrayOf(
                12.0f, 12.0f, 12.0f, 12.0f, 12.0f, 12.0f, 12.0f, 12.0f
            ), Path.Direction.CCW
        )

        // The perimeter should never change
        unifiedPath.addPath(scaledPerimeter)

        fillPaint.color = levelColor

        // Deal with unifiedPath clipping before it draws
        if (charging) {
            // Clip out the bolt shape
            unifiedPath.op(scaledBolt, Path.Op.DIFFERENCE)
            levelPath.op(scaledBolt, Path.Op.DIFFERENCE)
            if (!invertFillIcon) {
                c.drawPath(scaledBolt, fillPaint)
            }
        }

        // Dual tone means we draw the shape again, clipped to the charge level
        fillPaint.color = boltColor
        c.drawPath(unifiedPath, fillPaint)
        fillPaint.color = levelColor
        c.save()
        c.clipRect(
            bounds.left + (fillRect.width() * (1 - fillFraction + 0.15f)),
            bounds.top.toFloat(),
            bounds.left.toFloat() + bounds.width(),
            bounds.bottom.toFloat()
        )
        c.drawPath(scaledFill, fillPaint)
        c.restore()

        if (charging) {
            val xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            fillColorStrokePaint.xfermode = xfermode

            c.drawPath(scaledBolt, fillColorStrokePaint)

            fillPaint.color = boltColor
            c.drawPath(scaledBolt, fillPaint)
            fillPaint.color = levelColor
        }
        c.restore()
    }

    private fun batteryColorForLevel(level: Int): Int {
        return when {
            charging -> 0xFF34C759.toInt()
            powerSaveEnabled -> 0xFFFFCC0A.toInt()
            level > 20 -> fillColor
            level >= 0 -> 0xFFFF3B30.toInt()
            else -> getColorForLevel(level)
        }
    }

    private fun getColorForLevel(level: Int): Int {
        var thresh: Int
        var color = 0
        var i = 0
        while (i < colorLevels.size) {
            thresh = colorLevels[i]
            color = colorLevels[i + 1]
            if (level <= thresh) {

                // Respect tinting for "normal" level
                return if (i == colorLevels.size - 2) {
                    fillColor
                } else {
                    color
                }
            }
            i += 2
        }
        return color
    }

    /**
     * Alpha is unused internally, and should be defined in the colors passed to {@link setColors}.
     * Further, setting an alpha for a dual tone battery meter doesn't make sense without bounds
     * defining the minimum background fill alpha. This is because fill + background must be equal
     * to the net alpha passed in here.
     */
    override fun setAlpha(alpha: Int) {
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        fillColorStrokePaint.colorFilter = colorFilter
        dualToneBackgroundFill.colorFilter = colorFilter
    }

    /**
     * Deprecated, but required by Drawable
     */
    @Deprecated(
        "Deprecated in Java",
        ReplaceWith("PixelFormat.OPAQUE", "android.graphics.PixelFormat"),
    )
    override fun getOpacity(): Int {
        return PixelFormat.OPAQUE
    }

    override fun getIntrinsicHeight(): Int {
        return intrinsicHeight
    }

    override fun getIntrinsicWidth(): Int {
        return intrinsicWidth
    }

    /**
     * Set the fill level
     */
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun setBatteryLevel(l: Int) {
        invertFillIcon = if (l >= 67) true else if (l <= 33) false else invertFillIcon
        batteryLevel = l
        levelColor = batteryColorForLevel(batteryLevel)
        invalidateSelf()
    }

    fun getBatteryLevel(): Int {
        return batteryLevel
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        updateSize()
    }

    fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        padding.left = left
        padding.top = top
        padding.right = right
        padding.bottom = bottom

        updateSize()
    }

    override fun setColors(fgColor: Int, bgColor: Int, singleToneColor: Int) {
        fillColor = fgColor
        boltColor = singleToneColor

        fillPaint.color = singleToneColor
        fillColorStrokePaint.color = singleToneColor

        backgroundColor = bgColor
        dualToneBackgroundFill.color = bgColor

        // Also update the level color, since fillColor may have changed
        levelColor = batteryColorForLevel(batteryLevel)

        invalidateSelf()
    }

    private fun postInvalidate() {
        unscheduleSelf(invalidateRunnable)
        scheduleSelf(invalidateRunnable, 0)
    }

    private fun updateSize() {
        val b = bounds
        if (b.isEmpty) {
            scaleMatrix.setScale(1f, 1f)
        } else {
            scaleMatrix.setScale((b.right / WIDTH), (b.bottom / HEIGHT))
        }

        perimeterPath.transform(scaleMatrix, scaledPerimeter)
        errorPerimeterPath.transform(scaleMatrix, scaledErrorPerimeter)
        fillMask.transform(scaleMatrix, scaledFill)
        scaledFill.computeBounds(fillRect, true)
        boltPath.transform(scaleMatrix, scaledBolt)
        plusPath.transform(scaleMatrix, scaledPlus)

        // It is expected that this view only ever scale by the same factor in each dimension, so
        // just pick one to scale the strokeWidths
        val scaledStrokeWidth =
            (b.right / WIDTH * PROTECTION_STROKE_WIDTH).coerceAtLeast(PROTECTION_MIN_STROKE_WIDTH)

        fillColorStrokePaint.strokeWidth = scaledStrokeWidth
        fillColorStrokeProtection.strokeWidth = scaledStrokeWidth
    }

    private fun loadPaths() {
        val pathString =
            "M5.75,0.75L18.25,0.75A5.25 5.25 0 0 1 23.50,6.00L23.50,6.00A5.25 5.25 0 0 1 18.25,11.25L5.75,11.25A5.25 5.25 0 0 1 0.50,6.00L0.50,6.00A5.25 5.25 0 0 1 5.75,0.75zM1.75,6.00L1.75,6.00A4.00 4.00 0 0 0 5.75,10.00L18.25,10.00A4.00 4.00 0 0 0 22.25,6.00L22.25,6.00A4.00 4.00 0 0 0 18.25,2.00L5.75,2.00A4.00 4.00 0 0 0 1.75,6.00z"
        perimeterPath.set(PathParser.createPathFromPathData(pathString))
        perimeterPath.computeBounds(RectF(), true)

        val errorPathString =
            "M5.75,0.75L18.25,0.75A5.25 5.25 0 0 1 23.50,6.00L23.50,6.00A5.25 5.25 0 0 1 18.25,11.25L5.75,11.25A5.25 5.25 0 0 1 0.50,6.00L0.50,6.00A5.25 5.25 0 0 1 5.75,0.75zM1.75,6.00L1.75,6.00A4.00 4.00 0 0 0 5.75,10.00L18.25,10.00A4.00 4.00 0 0 0 22.25,6.00L22.25,6.00A4.00 4.00 0 0 0 18.25,2.00L5.75,2.00A4.00 4.00 0 0 0 1.75,6.00z"
        errorPerimeterPath.set(PathParser.createPathFromPathData(errorPathString))
        errorPerimeterPath.computeBounds(RectF(), true)

        val fillMaskString =
            "M3.14,6.00L3.14,6.00A3.03 3.03 0 0 0 6.17,9.03L17.83,9.03A3.03 3.03 0 0 0 20.86,6.00L20.86,6.00A3.03 3.03 0 0 0 17.83,2.97L6.17,2.97A3.03 3.03 0 0 0 3.14,6.00z"
        fillMask.set(PathParser.createPathFromPathData(fillMaskString))
        // Set the fill rect so we can calculate the fill properly
        fillMask.computeBounds(fillRect, true)

        val boltPathString =
            "M9.11,6.11L13.68,1.21C13.91,0.93,14.25,1.15,14.06,1.58L12.77,4.50C12.39,5.40,12.86,5.41,13.49,5.39L14.65,5.36C15.15,5.36,15.15,5.63,14.89,5.89L10.32,10.79C10.09,11.07,9.75,10.85,9.94,10.42L11.23,7.50C11.61,6.60,11.14,6.59,10.51,6.61L9.35,6.64C8.85,6.64,8.85,6.37,9.11,6.11z"
        boltPath.set(PathParser.createPathFromPathData(boltPathString))

        val plusPathString =
            "M5.75,0.75L18.25,0.75A5.25 5.25 0 0 1 23.50,6.00L23.50,6.00A5.25 5.25 0 0 1 18.25,11.25L5.75,11.25A5.25 5.25 0 0 1 0.50,6.00L0.50,6.00A5.25 5.25 0 0 1 5.75,0.75zM1.75,6.00L1.75,6.00A4.00 4.00 0 0 0 5.75,10.00L18.25,10.00A4.00 4.00 0 0 0 22.25,6.00L22.25,6.00A4.00 4.00 0 0 0 18.25,2.00L5.75,2.00A4.00 4.00 0 0 0 1.75,6.00z"
        plusPath.set(PathParser.createPathFromPathData(plusPathString))

        dualTone = true
    }

    companion object {
        private val TAG = LandscapeBatteryDrawableMIUIPill::class.java.simpleName
        private const val WIDTH = 24f
        private const val HEIGHT = 12f
        private const val CRITICAL_LEVEL = 15

        // On a 12x20 grid, how wide to make the fill protection stroke.
        // Scales when our size changes
        private const val PROTECTION_STROKE_WIDTH = 3f

        // Arbitrarily chosen for visibility at small sizes
        private const val PROTECTION_MIN_STROKE_WIDTH = 6f
    }
}