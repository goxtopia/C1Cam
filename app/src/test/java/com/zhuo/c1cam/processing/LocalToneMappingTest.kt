package com.zhuo.c1cam.processing

import com.zhuo.c1cam.camera.PreviewAnalysisPolicy
import com.zhuo.c1cam.camera.PreviewFrameGeometry
import com.zhuo.c1cam.settings.PreviewDisplayMode
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class LocalToneMappingTest {
    @Test fun darkEnvironmentIsLiftedButBlackMaterialInBrightSurroundingsIsProtected() {
        val l = LocalToneMapping.logLuma(.025f)
        val dark = LocalToneMapping.gainStops(l,l,l)
        val bright = LocalToneMapping.gainStops(l,l,LocalToneMapping.logLuma(.5f))
        assertTrue(dark > 1.3f)
        assertEquals(0f,bright,.001f)
    }
    @Test fun textureContrastIsPreservedWhenOnlyBaseIsCompressed() {
        val base = LocalToneMapping.logLuma(.04f)
        val a = base-.2f; val b = base+.2f
        val mappedA = a+LocalToneMapping.gainStops(a,base,base)
        val mappedB = b+LocalToneMapping.gainStops(b,base,base)
        assertEquals(b-a,mappedB-mappedA,.00001f)
    }
    @Test fun blackStaysBlackAndHighlightsRemainOrdered() {
        assertEquals(0f,LocalToneMapping.gainStops(-12f,-12f,-12f),.0001f)
        var previous = 0f
        for (i in 1..1000) {
            val y = i/1000f; val l = LocalToneMapping.logLuma(y)
            val out = y*2f.pow(LocalToneMapping.gainStops(l,l,l))
            assertTrue("Nonmonotonic at $y",out >= previous)
            assertTrue(out.isFinite())
            previous = out
        }
        assertTrue(previous < 1f && previous > .8f)
    }
    @Test fun meteringTargetRetainsHighlightBiasInLinearLight() {
        val normal = .42f
        assertEquals(normal,LocalToneMapping.meteringTarget(normal,false),0f)
        val protected = LocalToneMapping.meteringTarget(normal,true)
        assertEquals(LocalToneMapping.HIGHLIGHT_BIAS_EV,
            log2(LocalToneMapping.linear(protected)/LocalToneMapping.linear(normal)),.00001f)
    }
    @Test fun xpanSoftwareAeAlsoRespectsUserEvAndBoundsTheTarget() {
        val normal = .42f
        val requested = LocalToneMapping.meteringTarget(normal,true,1f)
        assertEquals(.3f,log2(LocalToneMapping.linear(requested)/LocalToneMapping.linear(normal)),.00001f)
        assertEquals(.97f,LocalToneMapping.meteringTarget(normal,true,10f),0f)
        assertEquals(.03f,LocalToneMapping.meteringTarget(normal,true,-10f),0f)
    }
    @Test fun wdrCameraPreviewUsesAnalysisAndPreservesFullFrameForAllOrientations() {
        assertTrue(PreviewAnalysisPolicy.forMode(PreviewDisplayMode.CAMERA,false,true).shouldProcessFrame(1))
        assertFalse(PreviewAnalysisPolicy.forMode(PreviewDisplayMode.CAMERA).shouldProcessFrame(1))
        for (angle in listOf(0,90,180,270)) {
            val geometry = PreviewFrameGeometry.forCamera(1200,900,angle)
            assertEquals(if (angle%180==0) 1200 else 900,geometry.outputWidth)
            assertEquals(if (angle%180==0) 900 else 1200,geometry.outputHeight)
        }
    }
}
