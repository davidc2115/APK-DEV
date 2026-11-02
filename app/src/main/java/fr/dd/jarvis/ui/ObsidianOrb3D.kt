package fr.dd.jarvis.ui

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Première scène 3D native de l'Orbe Obsidienne.
 * OpenGL ES 2.0 évite d'ajouter un moteur externe et fonctionne hors ligne.
 * Les notes et relations affichées dans l'overlay viennent exclusivement de l'index Room.
 */
@Composable
fun ObsidianOrb3D(app: fr.dd.jarvis.JarvisApp) {
    val nodes by app.knowledgeRepository.observeNodes().collectAsState(initial = emptyList())
    val relations by app.knowledgeRepository.observeRelations().collectAsState(initial = emptyList())

    var selectedNodeId by remember { mutableStateOf<String?>(null) }
    val selectedNode = nodes.firstOrNull { it.id == selectedNodeId }
    val selectedRelations = relations.filter { it.fromNodeId == selectedNodeId || it.toNodeId == selectedNodeId }
    val surfaceHolder = remember { arrayOfNulls<ObsidianOrbSurface>(1) }
    Box(Modifier.fillMaxSize().background(Color(0xFF030208))) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                ObsidianOrbSurface(context).also { surface ->
                    surfaceHolder[0] = surface
                    surface.onNodeSelected = { id -> selectedNodeId = id }
                }
            },
            update = { view -> view.setGraph(nodes, relations); view.setSelectedNode(selectedNodeId) }
        )
        // Le cerveau et Jörmungand sont désormais rendus dans la scène OpenGL (géométrie 3D).
        if (selectedNode != null) {
            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 112.dp)
                    .background(Color(0xE6170D25), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(selectedNode.title, color = Color(0xFFF0E5FF), fontSize = 14.sp)
                Text(selectedNode.sourcePath, color = Color(0xFFB69ACF), fontSize = 10.sp)
                Text("${selectedRelations.size} relation(s) · ${selectedNode.type}", color = Color(0xFF9D84BD), fontSize = 10.sp)
                Text("FERMER", Modifier.clickable { selectedNodeId = null }, color = Color(0xFFD2B5FF), fontSize = 9.sp)
            }
        }
    }
}

private class ObsidianOrbSurface(context: Context) : GLSurfaceView(context) {
    private val orbRenderer = ObsidianOrbRenderer()
    var onNodeSelected: ((String) -> Unit)? = null
    private var downX = 0f
    private var downY = 0f
    fun setGraph(nodes: List<fr.dd.jarvis.data.graph.GraphNodeEntity>, relations: List<fr.dd.jarvis.data.graph.GraphRelationEntity>) {
        // Les notes sont d'abord regroupées par leur vrai dossier Obsidian : chaque dossier forme
        // une petite constellation distincte sur la coque externe, au lieu d'un nuage uniforme.
        // Les positions restent déterministes : l'ordre et l'ajout d'une note ne déplacent pas
        // arbitrairement les autres groupes.
        val groups = nodes
            .filter { it.availability == "PRESENT" }
            .groupBy { it.folder.ifBlank { "Racine" } }
            .toSortedMap()
        val positions = HashMap<String, FloatArray>(nodes.size)
        val groupList = groups.entries.toList()
        groupList.forEachIndexed { groupIndex, entry ->
            val groupCount = groupList.size.coerceAtLeast(1)
            val centerY = 1f - 2f * (groupIndex + 0.5f) / groupCount
            val centerRadius = sqrt(max(0f, 1f - centerY * centerY))
            val centerTheta = groupIndex * 2.3999632f
            val center = floatArrayOf(
                centerRadius * cos(centerTheta),
                centerY,
                centerRadius * sin(centerTheta)
            )

            // Base tangentielle au point central : les notes d'un même dossier restent proches,
            // mais sont étalées sur une calotte sphérique pour éviter les amas illisibles.
            var ux = -center[2]
            var uy = 0f
            var uz = center[0]
            val uLength = sqrt(ux * ux + uy * uy + uz * uz).coerceAtLeast(0.0001f)
            ux /= uLength; uy /= uLength; uz /= uLength
            val vx = center[1] * uz - center[2] * uy
            val vy = center[2] * ux - center[0] * uz
            val vz = center[0] * uy - center[1] * ux

            val members = entry.value.sortedWith(compareBy<fr.dd.jarvis.data.graph.GraphNodeEntity> { it.title.lowercase() }.thenBy { it.id })
            members.forEachIndexed { memberIndex, node ->
                val localRadius = if (members.size <= 1) 0f else 0.34f * sqrt((memberIndex + 0.5f) / members.size)
                val localAngle = memberIndex * 2.3999632f
                val dx = center[0] + localRadius * (ux * cos(localAngle) + vx * sin(localAngle))
                val dy = center[1] + localRadius * (uy * cos(localAngle) + vy * sin(localAngle))
                val dz = center[2] + localRadius * (uz * cos(localAngle) + vz * sin(localAngle))
                val length = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.0001f)
                val shellRadius = 1.52f
                positions[node.id] = floatArrayOf(shellRadius * dx / length, shellRadius * dy / length, shellRadius * dz / length)
            }
        }

        val visible = nodes.filter { positions.containsKey(it.id) }.sortedBy { it.id }
        nodeIds = visible.map { it.id }
        nodePositions = visible.map { positions.getValue(it.id) }
        graphPoints = nodePositions.flatMap { it.toList() }.toFloatArray()

        // Seuls les liens explicites entre deux notes réellement indexées sont dessinés.
        // Les références non résolues et les relations suggérées ne deviennent jamais de faux liens.
        val edges = ArrayList<Float>()
        relations.asSequence()
            .filter { it.origin == "EXPLICIT" && positions.containsKey(it.fromNodeId) && positions.containsKey(it.toNodeId) }
            .forEach { relation ->
                val a = positions[relation.fromNodeId]!!
                val b = positions[relation.toNodeId]!!
                edges.addAll(a.toList())
                edges.addAll(b.toList())
            }
        graphLines = edges.toFloatArray()
        pointBuffer = makeBuffer(graphPoints)
        lineBuffer = makeBuffer(graphLines)
        val selectedIndex = nodeIds.indexOf(selectedNodeId)
        selectedPointBuffer = if (selectedIndex >= 0) nodePositions.getOrNull(selectedIndex)?.let { makeBuffer(it) } else null
    }

    private fun makeBuffer(values: FloatArray): java.nio.FloatBuffer {
        val b = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        b.put(values); b.position(0); return b
    }

    fun touch(x: Float, y: Float, dx: Float, dy: Float) { lastX = x; lastY = y }
    fun hitTest(x: Float, y: Float): String? {
        val matrix = hitMatrix
        var bestId: String? = null
        var bestDistance = 30f * 30f
        nodePositions.forEachIndexed { index, point ->
            val clip = FloatArray(4)
            Matrix.multiplyMV(clip, 0, matrix, 0, floatArrayOf(point[0], point[1], point[2], 1f), 0)
            if (clip[3] > 0.01f) {
                val sx = (clip[0] / clip[3] + 1f) * surfaceWidth * 0.5f
                val sy = (1f - clip[1] / clip[3]) * surfaceHeight * 0.5f
                val dx = sx - x; val dy = sy - y
                val distance = dx * dx + dy * dy
                if (distance < bestDistance) { bestDistance = distance; bestId = nodeIds.getOrNull(index) }
            }
        }
        return bestId
    }
    fun drag(x: Float, y: Float) {
        angleY += (x - lastX) * 0.55f
        angleX = (angleX + (y - lastY) * 0.45f).coerceIn(-85f, 85f)
        lastX = x
        lastY = y
    }
    fun setPinchStart(value: Float) { pinchStart = value }
    fun pinch(value: Float) {
        if (pinchStart > 0f && value > 0f) zoom = (zoom * (value / pinchStart)).coerceIn(0.62f, 2.5f)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.012f, 0.008f, 0.022f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        graphProgram = createProgram(GRAPH_VERTEX_SHADER, GRAPH_FRAGMENT_SHADER)
        vertexBuffer = createSphere(64, 48)
        vertexCount = 64 * 48 * 6
        detailSphere = createSphere(24, 20)
        detailVertexCount = 24 * 20 * 6
        serpentBuffer = createSerpentTube(180, 14)
        serpentVertexCount = 180 * 14 * 6
        // Recul suffisant pour garder la constellation complète dans le cadre en portrait,
// sans couper les nœuds extérieurs sur les écrans étroits.
        Matrix.setLookAtM(view, 0, 0f, 0f, 4.0f, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width; surfaceHeight = height
        GLES20.glViewport(0, 0, width, height)
        val ratio = width.toFloat() / max(height, 1)
        Matrix.perspectiveM(projection, 0, 46f, ratio, 0.1f, 20f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (program == 0) return
        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, angleY, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, angleX, 1f, 0f, 0f)
        Matrix.scaleM(model, 0, zoom, zoom, zoom)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        hitMatrix = mvp.copyOf()

        GLES20.glUseProgram(program)
        val pos = GLES20.glGetAttribLocation(program, "aPosition")
        val normal = GLES20.glGetAttribLocation(program, "aNormal")
        val buffer = vertexBuffer ?: return
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(pos)
        GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, 24, buffer)
        buffer.position(3)
        GLES20.glEnableVertexAttribArray(normal)
        GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, 24, buffer)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uModel"), 1, false, model, 0)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uTint"), 0.72f, 0.66f, 0.88f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uEmission"), 0f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)

        // Jörmungand : véritable corps tubulaire maillé, enroulé autour de l'obsidienne.
        serpentBuffer?.let { serpent ->
            serpent.position(0)
            GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, 24, serpent)
            serpent.position(3)
            GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, 24, serpent)
            GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uTint"), 0.34f, 0.13f, 0.55f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uEmission"), 0.12f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, serpentVertexCount)
        }

        // Cerveau bilobé en volume, placé devant la sphère : deux hémisphères organiques.
        val detail = detailSphere
        if (detail != null) {
            fun drawDetail(x: Float, y: Float, z: Float, sx: Float, sy: Float, sz: Float, tint: Float, emission: Float) {
                val part = model.copyOf()
                Matrix.translateM(part, 0, x, y, z)
                Matrix.scaleM(part, 0, sx, sy, sz)
                val pv = FloatArray(16)
                val partMvp = FloatArray(16)
                Matrix.multiplyMM(pv, 0, projection, 0, view, 0)
                Matrix.multiplyMM(partMvp, 0, pv, 0, part, 0)
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, partMvp, 0)
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uModel"), 1, false, part, 0)
                GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uTint"), 0.72f + tint * 0.28f, 0.32f + tint * 0.30f, 0.95f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uEmission"), emission)
                detail.position(0)
                GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, 24, detail)
                detail.position(3)
                GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, 24, detail)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, detailVertexCount)
            }
            // Deux lobes principaux, puis des replis organiques pour éviter l'aspect de deux simples billes.
            drawDetail(-0.19f, 0.035f, 1.01f, 0.37f, 0.31f, 0.22f, 0.65f, 0.42f)
            drawDetail(0.19f, 0.035f, 1.01f, 0.37f, 0.31f, 0.22f, 0.82f, 0.46f)
            drawDetail(-0.31f, 0.16f, 1.075f, 0.17f, 0.105f, 0.12f, 0.74f, 0.48f)
            drawDetail(-0.27f, -0.14f, 1.075f, 0.18f, 0.105f, 0.12f, 0.78f, 0.48f)
            drawDetail(-0.08f, 0.25f, 1.075f, 0.16f, 0.095f, 0.12f, 0.70f, 0.44f)
            drawDetail(0.08f, -0.23f, 1.075f, 0.16f, 0.095f, 0.12f, 0.82f, 0.48f)
            drawDetail(0.28f, 0.16f, 1.075f, 0.17f, 0.105f, 0.12f, 0.86f, 0.5f)
            drawDetail(0.30f, -0.12f, 1.075f, 0.17f, 0.105f, 0.12f, 0.90f, 0.48f)
        }
        GLES20.glDisableVertexAttribArray(pos)
        GLES20.glDisableVertexAttribArray(normal)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uModel"), 1, false, model, 0)

        // Les nœuds forment une constellation autour de la sphère : ils ne doivent pas disparaître derrière l'obsidienne.
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(graphProgram)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(graphProgram, "uMvp"), 1, false, mvp, 0)
        val graphPos = GLES20.glGetAttribLocation(graphProgram, "aPosition")
        lineBuffer?.let { b ->
            b.position(0); GLES20.glVertexAttribPointer(graphPos, 3, GLES20.GL_FLOAT, false, 12, b)
            GLES20.glEnableVertexAttribArray(graphPos)
            GLES20.glLineWidth(1.7f)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(graphProgram, "uColor"), 0.72f, 0.34f, 1f, 0.88f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, graphLines.size / 3)
            GLES20.glDisableVertexAttribArray(graphPos)
        }
        pointBuffer?.let { b ->
            b.position(0); GLES20.glVertexAttribPointer(graphPos, 3, GLES20.GL_FLOAT, false, 12, b)
            GLES20.glEnableVertexAttribArray(graphPos)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(graphProgram, "uColor"), 0.82f, 0.62f, 1f, 1f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(graphProgram, "uPointSize"), 26f)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, graphPoints.size / 3)
            selectedPointBuffer?.let { selected ->
                selected.position(0)
                GLES20.glVertexAttribPointer(graphPos, 3, GLES20.GL_FLOAT, false, 12, selected)
                GLES20.glUniform4f(GLES20.glGetUniformLocation(graphProgram, "uColor"), 0.96f, 0.72f, 1f, 1f)
                GLES20.glUniform1f(GLES20.glGetUniformLocation(graphProgram, "uPointSize"), 54f)
                GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1)
            }
            GLES20.glDisableVertexAttribArray(graphPos)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    /** Tube fermé en maillage triangulé : corps continu de serpent, et non un trait 2D. */
    private fun createSerpentTube(steps: Int, sides: Int): java.nio.FloatBuffer {
        val values = ArrayList<Float>(steps * sides * 36)
        fun point(t: Double, side: Double): FloatArray {
            val a = t * Math.PI * 2.0
            val cx = 1.045 * cos(a)
            val cy = 0.91 * sin(a)
            val cz = 0.18 + 0.30 * sin(a * 2.0 + 0.55)
            val tx = -1.045 * sin(a)
            val ty = 0.91 * cos(a)
            val tl = kotlin.math.sqrt(tx * tx + ty * ty)
            val nx = ty / tl
            val ny = -tx / tl
            val q = side * Math.PI * 2.0
            val radius = 0.073 + 0.012 * (0.5 + 0.5 * sin(a * 3.0))
            val radial = cos(q) * radius
            return floatArrayOf(
                (cx + nx * radial).toFloat(),
                (cy + ny * radial).toFloat(),
                (cz + sin(q) * radius).toFloat(),
                (nx * cos(q)).toFloat(),
                (ny * cos(q)).toFloat(),
                sin(q).toFloat()
            )
        }
        fun add(v: FloatArray) { values.add(v[0]); values.add(v[1]); values.add(v[2]); values.add(v[3]); values.add(v[4]); values.add(v[5]) }
        for (i in 0 until steps) for (j in 0 until sides) {
            val t0 = i.toDouble() / steps
            val t1 = (i + 1).toDouble() / steps
            val s0 = j.toDouble() / sides
            val s1 = (j + 1).toDouble() / sides
            val a = point(t0, s0); val b = point(t1, s0); val c = point(t1, s1); val d = point(t0, s1)
            add(a); add(b); add(c); add(a); add(c); add(d)
        }
        val bytes = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
        return bytes.asFloatBuffer().apply { values.forEach { put(it) }; position(0) }
    }

    private fun createSphere(stacks: Int, slices: Int): java.nio.FloatBuffer {
        val values = ArrayList<Float>(stacks * slices * 36)
        fun vertex(phi: Double, theta: Double) {
            val x = (sin(phi) * cos(theta)).toFloat()
            val y = cos(phi).toFloat()
            val z = (sin(phi) * sin(theta)).toFloat()
            values.add(x); values.add(y); values.add(z)
            values.add(x); values.add(y); values.add(z)
        }
        for (i in 0 until stacks) {
            val p0 = Math.PI * i / stacks
            val p1 = Math.PI * (i + 1) / stacks
            for (j in 0 until slices) {
                val t0 = 2.0 * Math.PI * j / slices
                val t1 = 2.0 * Math.PI * (j + 1) / slices
                vertex(p0, t0); vertex(p1, t0); vertex(p1, t1)
                vertex(p0, t0); vertex(p1, t1); vertex(p0, t1)
            }
        }
        val bytes = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
        return bytes.asFloatBuffer().apply { values.forEach { put(it) }; position(0) }
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
            val status = IntArray(1)
            GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "Échec de liaison du shader 3D" }
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    companion object {
        private const val GRAPH_VERTEX_SHADER = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            uniform float uPointSize;
            void main() { gl_Position = uMvp * vec4(aPosition, 1.0); gl_PointSize = uPointSize; }
        """
        private const val GRAPH_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                vec2 p = gl_PointCoord - vec2(0.5);
                float glow = 1.0 - smoothstep(0.18, 0.5, length(p));
                gl_FragColor = vec4(uColor.rgb, uColor.a * glow);
            }
        """
        private const val VERTEX_SHADER = """
            uniform mat4 uMvp;
            uniform mat4 uModel;
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            varying vec3 vPosition;
            varying vec3 vNormal;
            uniform vec3 uTint;
            uniform float uEmission;
            void main() {
                vec4 world = uModel * vec4(aPosition, 1.0);
                vPosition = world.xyz;
                vNormal = normalize(mat3(uModel) * aNormal);
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }
        """
        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec3 uTint;
            uniform float uEmission;
            varying vec3 vPosition;
            varying vec3 vNormal;

            float hash31(vec3 p) {
                p = fract(p * 0.1031);
                p += dot(p, p.yzx + 33.33);
                return fract((p.x + p.y) * p.z);
            }
            float noise3(vec3 p) {
                vec3 i = floor(p);
                vec3 f = fract(p);
                f = f * f * (3.0 - 2.0 * f);
                return mix(mix(mix(hash31(i), hash31(i + vec3(1,0,0)), f.x),
                               mix(hash31(i + vec3(0,1,0)), hash31(i + vec3(1,1,0)), f.x), f.y),
                           mix(mix(hash31(i + vec3(0,0,1)), hash31(i + vec3(1,0,1)), f.x),
                               mix(hash31(i + vec3(0,1,1)), hash31(i + vec3(1,1,1)), f.x), f.y), f.z);
            }
            float fbm(vec3 p) {
                float v = 0.0;
                float a = 0.5;
                for (int i = 0; i < 4; i++) {
                    v += noise3(p) * a;
                    p *= 2.03;
                    a *= 0.5;
                }
                return v;
            }
            void main() {
                vec3 n = normalize(vNormal);
                vec3 viewDir = normalize(vec3(0.0, 0.0, 3.3) - vPosition);
                vec3 lightDir = normalize(vec3(-0.55, 0.8, 1.0));
                float diffuse = max(dot(n, lightDir), 0.0);
                float specular = pow(max(dot(reflect(-lightDir, n), viewDir), 0.0), 38.0);
                float fresnel = pow(1.0 - max(dot(n, viewDir), 0.0), 3.0);

                float grain = fbm(vPosition * 19.0);
                float warp = fbm(vPosition * 5.5);
                float crackField = abs(fbm(vPosition * 12.0 + warp * 2.2) - 0.48);
                float veins = 1.0 - smoothstep(0.012, 0.055, crackField);
                float fineVeins = 1.0 - smoothstep(0.006, 0.025, abs(sin((vPosition.x * 17.0 + vPosition.y * 13.0 + warp * 8.0) * 2.1)));
                veins = max(veins, fineVeins * 0.24);

                vec3 base = (vec3(0.012, 0.009, 0.022) + grain * vec3(0.025, 0.018, 0.038)) * uTint;
                vec3 purple = vec3(0.38, 0.12, 0.72) * veins * (0.55 + warp);
                vec3 color = base * (0.34 + diffuse * 0.48);
                color += purple;
                color += vec3(0.28, 0.13, 0.48) * fresnel * 0.65;
                color += vec3(0.72, 0.48, 1.0) * specular * 0.9;
                float innerGlow = exp(-length(vPosition.xy) * 8.0) * 0.22;
                color += vec3(0.34, 0.08, 0.68) * innerGlow;
                color += uTint * uEmission * (0.55 + grain * 0.45);
                gl_FragColor = vec4(color, 1.0);
            }
        """
    }
}
