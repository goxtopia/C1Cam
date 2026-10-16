package com.zhuo.c1cam.processing

import android.opengl.GLES30 as G
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Three GPU passes, no CPU readback. Context and resources belong to CaptureGlRenderer. */
internal class LocalToneMappingGl {
    private val textures = IntArray(3)
    private val programs = IntArray(3)
    private var framebuffer = 0
    private var width = 0
    private var height = 0
    private var guideWidth = 0
    private var guideHeight = 0

    fun render(w: Int, h: Int, rgb: Boolean, source: IntArray): Int {
        if (programs[0] == 0) initialize()
        if (w != width || h != height) {
            width = w; height = h
            val scale = min(1f, LocalToneMapping.GUIDE_SIZE.toFloat() / max(w,h))
            guideWidth = (w * scale).roundToInt().coerceAtLeast(1)
            guideHeight = (h * scale).roundToInt().coerceAtLeast(1)
            for (i in textures.indices) {
                G.glBindTexture(G.GL_TEXTURE_2D, textures[i])
                G.glTexImage2D(G.GL_TEXTURE_2D, 0, G.GL_RGBA8,
                    if (i == 2) w else guideWidth, if (i == 2) h else guideHeight,
                    0, G.GL_RGBA, G.GL_UNSIGNED_BYTE, null)
            }
        }
        G.glBindFramebuffer(G.GL_FRAMEBUFFER, framebuffer)
        for (pass in 0..2) {
            val p = programs[pass]
            G.glUseProgram(p)
            G.glFramebufferTexture2D(G.GL_FRAMEBUFFER, G.GL_COLOR_ATTACHMENT0, G.GL_TEXTURE_2D, textures[pass], 0)
            check(G.glCheckFramebufferStatus(G.GL_FRAMEBUFFER) == G.GL_FRAMEBUFFER_COMPLETE) { "WDR framebuffer incomplete" }
            G.glViewport(0, 0, if (pass == 2) w else guideWidth, if (pass == 2) h else guideHeight)
            // All samplers use distinct units; no texture is sampled while attached for output.
            for (i in 0..2) bind(p, arrayOf("uY", "uU", "uV")[i], i, source[i])
            G.glUniform1i(G.glGetUniformLocation(p, "uRgb"), if (rgb) 1 else 0)
            G.glUniform2f(G.glGetUniformLocation(p, "uGuideSize"), guideWidth.toFloat(), guideHeight.toFloat())
            if (pass > 0) bind(p, "uGuide", 3, textures[0])
            if (pass == 2) bind(p, "uBase", 4, textures[1])
            G.glDrawArrays(G.GL_TRIANGLES, 0, 3)
            check(G.glGetError() == G.GL_NO_ERROR) { "WDR draw failed, pass=$pass" }
        }
        return textures[2]
    }

    private fun bind(p: Int, name: String, unit: Int, texture: Int) {
        G.glActiveTexture(G.GL_TEXTURE0 + unit)
        G.glBindTexture(G.GL_TEXTURE_2D, texture)
        G.glUniform1i(G.glGetUniformLocation(p, name), unit)
    }

    fun release() {
        G.glDeleteTextures(3, textures, 0)
        if (framebuffer != 0) G.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        programs.forEach { if (it != 0) G.glDeleteProgram(it) }
        textures.fill(0); programs.fill(0); framebuffer = 0; width = 0; height = 0
    }

    private fun initialize() {
        fun shader(type: Int, source: String): Int {
            val id = G.glCreateShader(type)
            G.glShaderSource(id, source); G.glCompileShader(id)
            val status = IntArray(1); G.glGetShaderiv(id, G.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = G.glGetShaderInfoLog(id); G.glDeleteShader(id)
                error("WDR shader: $log")
            }
            return id
        }
        for (i in 0..2) {
            val vs = shader(G.GL_VERTEX_SHADER, VERTEX)
            val fs = try { shader(G.GL_FRAGMENT_SHADER, HEADER + arrayOf(DOWNSAMPLE, BASE, RECOMBINE)[i]) }
                catch (e: Exception) { G.glDeleteShader(vs); throw e }
            val p = G.glCreateProgram(); programs[i] = p
            G.glAttachShader(p,vs); G.glAttachShader(p,fs); G.glLinkProgram(p)
            G.glDeleteShader(vs); G.glDeleteShader(fs)
            val status = IntArray(1); G.glGetProgramiv(p,G.GL_LINK_STATUS,status,0)
            check(status[0] != 0) { G.glGetProgramInfoLog(p) }
        }
        G.glGenTextures(3, textures, 0)
        for (t in textures) {
            G.glBindTexture(G.GL_TEXTURE_2D,t)
            G.glTexParameteri(G.GL_TEXTURE_2D,G.GL_TEXTURE_MIN_FILTER,G.GL_LINEAR)
            G.glTexParameteri(G.GL_TEXTURE_2D,G.GL_TEXTURE_MAG_FILTER,G.GL_LINEAR)
            G.glTexParameteri(G.GL_TEXTURE_2D,G.GL_TEXTURE_WRAP_S,G.GL_CLAMP_TO_EDGE)
            G.glTexParameteri(G.GL_TEXTURE_2D,G.GL_TEXTURE_WRAP_T,G.GL_CLAMP_TO_EDGE)
        }
        val ids = IntArray(1); G.glGenFramebuffers(1,ids,0); framebuffer = ids[0]
    }

    private companion object {
        val VERTEX = """#version 300 es
            out vec2 vUv;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                vUv = p; gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
        """.trimIndent()
        val HEADER = """#version 300 es
            precision highp float;
            precision highp sampler2D;
            uniform sampler2D uY, uU, uV, uGuide, uBase;
            uniform bool uRgb;
            uniform vec2 uGuideSize;
            in vec2 vUv;
            out vec4 frag;
            vec3 linearRgb(vec3 c) {
                return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
            }
            vec3 encoded(vec3 c) {
                return mix(12.92*c, 1.055*pow(max(c,vec3(0.0)),vec3(1.0/2.4))-0.055, step(vec3(0.0031308),c));
            }
            vec3 source(vec2 uv) {
                vec3 c;
                if (uRgb) c = texture(uY,uv).rgb;
                else {
                    float y=texture(uY,uv).r, u=texture(uU,uv).r-0.5, v=texture(uV,uv).r-0.5;
                    c=clamp(vec3(y+1.402*v,y-0.344136*u-0.714136*v,y+1.772*u),0.0,1.0);
                }
                return linearRgb(c);
            }
            float logLum(vec3 c) { return log2(max(dot(c,vec3(0.2126,0.7152,0.0722)),1.0/4096.0)); }
            // Two UNORM8 channels store a 16-bit log guide, avoiding float-FBO extensions.
            vec2 packLog(float v) {
                float n=floor(clamp((v+12.0)/12.0,0.0,1.0)*65535.0+0.5);
                return vec2(floor(n/256.0),mod(n,256.0))/255.0;
            }
            float unpackLog(vec2 v) { return dot(v,vec2(65280.0,255.0))/65535.0*12.0-12.0; }
        """.trimIndent() + "\n"
        val DOWNSAMPLE = """
            void main() {
                vec3 c=vec3(0.0);
                for(int y=0;y<4;y++) for(int x=0;x<4;x++)
                    c+=source(vUv+(vec2(x,y)-1.5)/(4.0*uGuideSize));
                frag=vec4(packLog(logLum(c/16.0)),0.0,1.0);
            }
        """
        val BASE = """
            void main() {
                float center=unpackLog(texture(uGuide,vUv).rg);
                float localStep=max(1.0,min(uGuideSize.x,uGuideSize.y)*0.035/3.0);
                float ambientStep=max(1.0,min(uGuideSize.x,uGuideSize.y)*0.18/3.0);
                float total=0.0, weights=0.0, ambient=0.0, ambientWeights=0.0;
                for(int y=-3;y<=3;y++) for(int x=-3;x<=3;x++) {
                    vec2 d=vec2(x,y);
                    float spatial=exp(-dot(d,d)/8.0);
                    float l=unpackLog(texture(uGuide,vUv+d*localStep/uGuideSize).rg);
                    float delta=l-center;
                    float w=spatial*exp(-delta*delta/(2.0*0.9*0.9));
                    total+=l*w; weights+=w;
                    ambient+=unpackLog(texture(uGuide,vUv+d*ambientStep/uGuideSize).rg)*spatial;
                    ambientWeights+=spatial;
                }
                frag=vec4(packLog(total/weights),packLog(ambient/ambientWeights));
            }
        """
        val RECOMBINE = """
            void main() {
                vec3 c=source(vUv);
                float l=logLum(c);
                vec2 p=vUv*uGuideSize-0.5, f=fract(p), origin=floor(p);
                float base=0.0, weights=0.0, ambient=0.0;
                for(int y=0;y<=1;y++) for(int x=0;x<=1;x++) {
                    vec2 uv=(origin+vec2(x,y)+0.5)/uGuideSize;
                    float bilinear=(x==0?1.0-f.x:f.x)*(y==0?1.0-f.y:f.y);
                    float delta=l-unpackLog(texture(uGuide,uv).rg);
                    float w=bilinear*(exp(-delta*delta/0.98)+0.00001);
                    vec4 b=texture(uBase,uv);
                    base+=unpackLog(b.rg)*w; weights+=w;
                    ambient+=unpackLog(b.ba)*bilinear;
                }
                base/=weights;
                float shadow=2.0*(1.0-smoothstep(log2(0.01),log2(0.5),base));
                float environment=1.0-smoothstep(log2(0.12),log2(0.5),ambient);
                float material=1.0-smoothstep(2.5,4.5,ambient-base);
                float blackGate=smoothstep(log2(0.0006),log2(0.006),l);
                float highlight=0.3*smoothstep(log2(0.35),log2(0.9),base);
                float mappedBase=base+shadow*environment*material*blackGate-highlight;
                float detail=l-base;
                c*=exp2(mappedBase+detail-l);
                c/=max(1.0,max(c.r,max(c.g,c.b)));
                frag=vec4(clamp(encoded(c),0.0,1.0),1.0);
            }
        """
    }
}
