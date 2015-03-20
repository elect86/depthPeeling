/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package depthPeeling.depthPeelingGL3;

import com.jogamp.newt.awt.NewtCanvasAWT;
import com.jogamp.newt.event.KeyEvent;
import com.jogamp.newt.event.KeyListener;
import com.jogamp.newt.event.MouseEvent;
import com.jogamp.newt.event.MouseListener;
import com.jogamp.newt.opengl.GLWindow;
import com.jogamp.opengl.util.GLBuffers;
import com.jogamp.opengl.util.texture.Texture;
import com.jogamp.opengl.util.texture.TextureIO;
import glutil.ViewData;
import glutil.ViewPole;
import glutil.ViewScale;
import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.media.opengl.GL3;
import javax.media.opengl.GLAutoDrawable;
import javax.media.opengl.GLCapabilities;
import javax.media.opengl.GLEventListener;
import javax.media.opengl.GLException;
import javax.media.opengl.GLProfile;
import jglm.Jglm;
import jglm.Mat4;
import jglm.Quat;
import jglm.Vec3;

/**
 *
 * @author gbarbieri
 */
public class DepthPeelingGL3 implements GLEventListener, KeyListener, MouseListener {

    private boolean depthPeelingMode = false;
    private int imageWidth = 1024;
    private int imageHeight = 768;
    public GLWindow glWindow;
    public NewtCanvasAWT newtCanvasAWT;
    private int[] depthTextureId;
    private int[] colorTextureId;
    private int[] fboId;
    private int[] colorBlenderTextureId;
    private int[] colorBlenderFboId;
    private float FOVY = 30.0f;
    private float zNear = 0.0001f;
    private float zFar = 10.0f;
    private boolean rotating = false;
    private boolean panning = false;
    private boolean scaling = false;
    private int geoPassesNumber;
    private int passesNumber = 10;
    private ProgramInit dpInit;
    private ProgramPeel dpPeel;
    private ProgramBlend dpBlend;
    private ProgramFinal dpFinal;
    private int[] queryId = new int[1];
    private float[] pos = new float[]{0.0f, 0.0f, 2.0f};
    private float[] rot = new float[]{0.0f, 0.0f};
    private float[] transl = new float[]{0.0f, 0.0f, 0.0f};
    private float scale = 1.0f;
    private float opacity = 0.3f;
    private float[] backgroundColor = new float[]{1.0f, 1.0f, 1.0f};
    private int[] quadVBO;
    private int[] quadVAO;
    private int[] modelVBO;
    private int[] modelVAO;
    private int[] floorVBO;
    private int[] floorVAO;
    private ViewPole viewPole;
    private int[] mvpMatrixesUBO;
    private float[] modelVertexAttributes;
    private int base;
    private Texture floorTexture;

    public DepthPeelingGL3() {
        initGL();
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        DepthPeelingGL3 depthPeeling = new DepthPeelingGL3();

        Frame frame = new Frame("Depth peeling GL3");

        frame.add(depthPeeling.newtCanvasAWT);

        frame.setSize(depthPeeling.glWindow.getWidth(), depthPeeling.glWindow.getHeight());

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent windowEvent) {
                System.exit(0);
            }
        });

        frame.setVisible(true);
    }

    private void initGL() {
        GLProfile gLProfile = GLProfile.getDefault();

        GLCapabilities gLCapabilities = new GLCapabilities(gLProfile);

        glWindow = GLWindow.create(gLCapabilities);

        newtCanvasAWT = new NewtCanvasAWT(glWindow);

        glWindow.setSize(imageWidth, imageHeight);

        glWindow.addGLEventListener(this);
        glWindow.addKeyListener(this);
        glWindow.addMouseListener(this);
    }

    @Override
    public void init(GLAutoDrawable glad) {
        System.out.println("init");

        glWindow.setAutoSwapBufferMode(false);

        GL3 gl3 = glad.getGL().getGL3();

        int projectionBlockBinding = 0;

        ViewData initialViewData = new ViewData(new Vec3(0.0f, 0.0f, 0.0f), new Quat(0.0f, 0.0f, 0.0f, 1.0f), 50.0f, 0.0f);

        ViewScale viewScale = new ViewScale(3.0f, 20.0f, 1.5f, 0.5f, 0.0f, 0.0f, 90.0f / 250.0f);

        viewPole = new ViewPole(initialViewData, viewScale, ViewPole.Projection.orthographic);

        initUBO(gl3, projectionBlockBinding);

        initDepthPeelingRenderTargets(gl3);
        gl3.glBindFramebuffer(GL3.GL_FRAMEBUFFER, 0);

        readAsciiStl(gl3);

        buildShaders(gl3, projectionBlockBinding);

        initFullScreenQuad(gl3);

        initFloor(gl3);

        base = 10000;
    }

    private void initUBO(GL3 gl3, int projectionBlockBinding) {

        mvpMatrixesUBO = new int[1];
        int size = 16 * 4;

        gl3.glGenBuffers(1, mvpMatrixesUBO, 0);
        gl3.glBindBuffer(GL3.GL_UNIFORM_BUFFER, mvpMatrixesUBO[0]);
        {
            gl3.glBufferData(GL3.GL_UNIFORM_BUFFER, size * 2, null, GL3.GL_DYNAMIC_DRAW);

            gl3.glBindBufferRange(GL3.GL_UNIFORM_BUFFER, projectionBlockBinding, mvpMatrixesUBO[0], 0, size * 2);
        }
        gl3.glBindBuffer(GL3.GL_UNIFORM_BUFFER, 0);
    }

    private void initFullScreenQuad(GL3 gl3) {

        initQuadVBO(gl3);

        initQuadVAO(gl3);

        Mat4 modelToClipMatrix = Jglm.orthographic2D(0, 1, 0, 1);

        dpFinal.bind(gl3);
        {
            gl3.glUniformMatrix4fv(dpFinal.getModelToClipMatrixUnLoc(), 1, false, modelToClipMatrix.toFloatArray(), 0);
        }
        dpFinal.unbind(gl3);

        dpBlend.bind(gl3);
        {
            gl3.glUniformMatrix4fv(dpBlend.getModelToClipMatrixUnLoc(), 1, false, modelToClipMatrix.toFloatArray(), 0);
        }
        dpBlend.unbind(gl3);
    }

    private void initQuadVAO(GL3 gl3) {

        quadVAO = new int[1];

        gl3.glBindBuffer(GL3.GL_ARRAY_BUFFER, quadVBO[0]);

        gl3.glGenVertexArrays(1, IntBuffer.wrap(quadVAO));
        gl3.glBindVertexArray(quadVAO[0]);
        {
            gl3.glEnableVertexAttribArray(0);
            {
                gl3.glVertexAttribPointer(0, 2, GL3.GL_FLOAT, false, 0, 0);
            }
        }
        gl3.glBindVertexArray(0);
    }

    private void initQuadVBO(GL3 gl3) {

        float[] vertexAttributes = new float[]{
            0.0f, 0.0f,
            1.0f, 0.0f,
            1.0f, 1.0f,
            0.0f, 1.0f};

        quadVBO = new int[1];

        gl3.glGenBuffers(1, IntBuffer.wrap(quadVBO));

        gl3.glBindBuffer(GL3.GL_ARRAY_BUFFER, quadVBO[0]);
        {
            FloatBuffer buffer = GLBuffers.newDirectFloatBuffer(vertexAttributes);

            gl3.glBufferData(GL3.GL_ARRAY_BUFFER, vertexAttributes.length * 4, buffer, GL3.GL_STATIC_DRAW);
        }
        gl3.glBindBuffer(GL3.GL_ARRAY_BUFFER, 0);
    }

    private void initFloor(GL3 gl3) {

        initFloorVBO(gl3);

        initFloorVAO(gl3);

        URL url = getClass().getResource("/depthPeeling/data/floor.png");

        try {
            floorTexture = TextureIO.newTexture(new File(url.getPath()), true);
        } catch (IOException | GLException ex) {
            Logger.getLogger(DepthPeelingGL3.class.getName()).log(Level.SEVERE, null, ex);
        }

        floorTexture.setTexParameteri(gl3, GL3.GL_TEXTURE_WRAP_S, GL3.GL_CLAMP_TO_EDGE);
        floorTexture.setTexParameteri(gl3, GL3.GL_TEXTURE_WRAP_T, GL3.GL_CLAMP_TO_EDGE);
        floorTexture.setTexParameteri(gl3, GL3.GL_TEXTURE_MIN_FILTER, GL3.GL_NEAREST);
        floorTexture.setTexParameteri(gl3, GL3.GL_TEXTURE_MAG_FILTER, GL3.GL_NEAREST);
    }

    private void initFloorVAO(GL3 gl3) {

        floorVAO = new int[1];

        gl3.glBindBuffer(GL3.GL_ARRAY_BUFFER, floorVBO[0]);

        gl3.glGenVertexArrays(1, IntBuffer.wrap(floorVAO));
        gl3.glBindVertexArray(floorVAO[0]);
        {
            gl3.glEnableVertexAttribArray(0);
            gl3.glEnableVertexAttribArray(1);
            {
                int stride = (3 + 2) * 4;
                int offset = 0;
                gl3.glVertexAttribPointer(0, 3, GL3.GL_FLOAT, false, stride, offset);
                offset = 3 * 4;
                gl3.glVertexAttribPointer(1, 2, GL3.GL_FLOAT, false, stride, offset);
            }
        }
        gl3.glBindVertexArray(0);
    }

    private void initFloorVBO(GL3 gl3) {

        float side = 5000f;

        float[] verticesAttributes = new float[]{
            -side, -side, 0f, 0f, 0f,
            -side, side, 0f, 0f, 1f,
            side, side, 0f, 1f, 1f,
            side, -side, 0f, 1f, 0f
        };

        floorVBO = new int[1];

        gl3.glGenBuffers(1, IntBuffer.wrap(floorVBO));

        gl3.glBindBuffer(GL3.GL_ARRAY_BUFFER, floorVBO[0]);
        {
            FloatBuffer buffer = GLBuffers.newDirectFloatBuffer(verticesAttributes);

            gl3.glBufferData(GL3.GL_ARRAY_BUFFER, verticesAttributes.length * 4, buffer, GL3.GL_STATIC_DRAW);
        }
        gl3.glBindBuffer(GL3.GL_ARRAY_BUFFER, 0);
    }

    private void buildShaders(GL3 gl3, int projectionBlockIndex) {
        System.out.print("buildShaders... ");

        String shadersFilepath = "/depthPeeling/depthPeelingGL3/shaders/";

        //dpInit = new ProgramInit(gl3, shadersFilepath, new String[]{"dpInit_VS.glsl"}, new String[]{"shade_FS.glsl", "dpInit_FS.glsl"}, projectionBlockIndex);
        dpInit = new ProgramInit(gl3, shadersFilepath, "dpInit_VS.glsl", "dpInit_FS.glsl", projectionBlockIndex);

//        dpPeel = new ProgramPeel(gl3, shadersFilepath, new String[]{"dpPeel_VS.glsl"}, new String[]{"shade_FS.glsl", "dpPeel_FS.glsl"}, projectionBlockIndex);
        dpPeel = new ProgramPeel(gl3, shadersFilepath, "dpPeel_VS.glsl", "dpPeel_FS.glsl", projectionBlockIndex);

        dpBlend = new ProgramBlend(gl3, shadersFilepath, "dpBlend_VS.glsl", "dpBlend_FS.glsl");

        dpFinal = new ProgramFinal(gl3, shadersFilepath, "dpFinal_VS.glsl", "dpFinal_FS.glsl");

        System.out.println("ok");
    }

    private void readAsciiStl(GL3 gl3) {
        try {
            FileReader fr;
            int vertexLocal = 0;
            int attributesGlobal = 0;

            URL url = getClass().getResource("/depthPeeling/data/frontlader5.stl");

            fr = new FileReader(new File(url.getPath()));
            BufferedReader br = new BufferedReader(fr);

            String line = "";
            String values[];
            float[] data = new float[3 * 3 * 2];
            float[] vertex = new float[9];
            float[] normal = new float[3];

            // Count triangles
            int triangles = 0;
            while ((line = br.readLine()) != null) {
                line = line.trim().toLowerCase();
                if (line.startsWith("facet")) {
                    triangles++;
                }
            }
            System.out.println("triangles: " + triangles);
            //  3 Vertexes, 3 coordinates, 2 attributes
            modelVertexAttributes = new float[triangles * 3 * 3 * 2];
            br.close();
            fr.close();

            fr = new FileReader(new File(url.getPath()));
            br = new BufferedReader(fr);
            line = "";
            //            int triangles_read = 0;

            while ((line = br.readLine()) != null) {

                line = line.trim().toLowerCase();

                // Read normals
                if (line.startsWith("facet")) {

                    int normalLocal = 0;
                    values = line.split(" ");

                    for (int i = 2; i < values.length; i++) {

                        if (!values[i].isEmpty()) {

                            normal[normalLocal] = Float.parseFloat(values[i]);
                            normalLocal++;
                        }
                    }
                }

                // Read points
                if (line.startsWith("vertex")) {

                    values = line.split(" ");

                    for (int i = 1; i < values.length; i++) {
                        if (!values[i].isEmpty()) {
                            vertex[vertexLocal] = Float.parseFloat(values[i]);
                            vertexLocal++;
                        }
                    }
                }

                if (vertexLocal == 9) {
                    //  Fill vertex and normals interleaved
                    for (int i = 0; i < 3; i++) {

                        data[i * 6] = vertex[i * 3];
                        data[i * 6 + 1] = vertex[i * 3 + 1];
                        data[i * 6 + 2] = vertex[i * 3 + 2];
                        data[i * 6 + 3] = normal[0];
                        data[i * 6 + 4] = normal[1];
                        data[i * 6 + 5] = normal[2];
                    }

//                    System.out.print("data[" + attributesGlobal + "] ");
//                    for (int i = 0; i < data.length; i++) {
//                        System.out.print(data[i] + " ");
//                    }
//                    System.out.println("");
                    System.arraycopy(data, 0, modelVertexAttributes, attributesGlobal * 3 * 3 * 2, data.length);

                    vertexLocal = 0;
                    attributesGlobal++;
                }
            }

            br.close();
            fr.close();

            System.out.println("Done, number of triangles: " + modelVertexAttributes.length / 18);
        } catch (FileNotFoundException ex) {
            Logger.getLogger(DepthPeelingGL3.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(DepthPeelingGL3.class.getName()).log(Level.SEVERE, null, ex);
        }

        initModelVBO(gl3);

        initModelVAO(gl3);
    }

    private void initModelVAO(GL3 gl3) {

        modelVAO = new int[1];

        gl3.glBindBuffer(GL3.GL_ARRAY_BUFFER, modelVBO[0]);

        gl3.glGenVertexArrays(1, IntBuffer.wrap(modelVAO));
        gl3.glBindVertexArray(modelVAO[0]);
        {
            gl3.glEnableVertexAttribArray(0);
            {
                gl3.glVertexAttribPointer(0, 3, GL3.GL_FLOAT, false, 6 * 4, 0);
            }
        }
        gl3.glBindVertexArray(0);
    }

    private void initModelVBO(GL3 gl3) {

        modelVBO = new int[1];

        gl3.glGenBuffers(1, IntBuffer.wrap(modelVBO));

        gl3.glBindBuffer(GL3.GL_ARRAY_BUFFER, modelVBO[0]);
        {
            FloatBuffer buffer = GLBuffers.newDirectFloatBuffer(modelVertexAttributes);

            gl3.glBufferData(GL3.GL_ARRAY_BUFFER, modelVertexAttributes.length * 4, buffer, GL3.GL_STATIC_DRAW);
        }
        gl3.glBindBuffer(GL3.GL_ARRAY_BUFFER, 0);
    }

    private void initDepthPeelingRenderTargets(GL3 gl3) {

        depthTextureId = new int[2];
        colorTextureId = new int[2];
        fboId = new int[2];

        gl3.glGenTextures(2, depthTextureId, 0);
        gl3.glGenTextures(2, colorTextureId, 0);
        gl3.glGenFramebuffers(2, fboId, 0);

        for (int i = 0; i < 2; i++) {

            gl3.glBindTexture(GL3.GL_TEXTURE_RECTANGLE, depthTextureId[i]);

            gl3.glTexParameteri(GL3.GL_TEXTURE_RECTANGLE, GL3.GL_TEXTURE_WRAP_S, GL3.GL_CLAMP_TO_EDGE);
            gl3.glTexParameteri(GL3.GL_TEXTURE_RECTANGLE, GL3.GL_TEXTURE_WRAP_T, GL3.GL_CLAMP_TO_EDGE);
            gl3.glTexParameteri(GL3.GL_TEXTURE_RECTANGLE, GL3.GL_TEXTURE_MIN_FILTER, GL3.GL_NEAREST);
            gl3.glTexParameteri(GL3.GL_TEXTURE_RECTANGLE, GL3.GL_TEXTURE_MAG_FILTER, GL3.GL_NEAREST);

            gl3.glTexImage2D(GL3.GL_TEXTURE_RECTANGLE, 0, GL3.GL_DEPTH_COMPONENT32F, imageWidth, imageHeight, 0, GL3.GL_DEPTH_COMPONENT, GL3.GL_FLOAT, null);

            gl3.glBindTexture(GL3.GL_TEXTURE_RECTANGLE, colorTextureId[i]);

            gl3.glTexParameteri(GL3.GL_TEXTURE_RECTANGLE, GL3.GL_TEXTURE_WRAP_S, GL3.GL_CLAMP_TO_EDGE);
            gl3.glTexParameteri(GL3.GL_TEXTURE_RECTANGLE, GL3.GL_TEXTURE_WRAP_T, GL3.GL_CLAMP_TO_EDGE);
            gl3.glTexParameteri(GL3.GL_TEXTURE_RECTANGLE, GL3.GL_TEXTURE_MIN_FILTER, GL3.GL_NEAREST);
            gl3.glTexParameteri(GL3.GL_TEXTURE_RECTANGLE, GL3.GL_TEXTURE_MAG_FILTER, GL3.GL_NEAREST);

            gl3.glTexImage2D(GL3.GL_TEXTURE_RECTANGLE, 0, GL3.GL_RGBA, imageWidth, imageHeight, 0, GL3.GL_RGBA, GL3.GL_FLOAT, null);

            gl3.glBindFramebuffer(GL3.GL_FRAMEBUFFER, fboId[i]);

            gl3.glFramebufferTexture2D(GL3.GL_FRAMEBUFFER, GL3.GL_DEPTH_ATTACHMENT, GL3.GL_TEXTURE_RECTANGLE, depthTextureId[i], 0);
            gl3.glFramebufferTexture2D(GL3.GL_FRAMEBUFFER, GL3.GL_COLOR_ATTACHMENT0, GL3.GL_TEXTURE_RECTANGLE, colorTextureId[i], 0);
        }

        colorBlenderTextureId = new int[1];

        gl3.glGenTextures(1, colorBlenderTextureId, 0);

        gl3.glBindTexture(GL3.GL_TEXTURE_RECTANGLE, colorBlenderTextureId[0]);

        gl3.glTexParameteri(GL3.GL_TEXTURE_RECTANGLE, GL3.GL_TEXTURE_WRAP_S, GL3.GL_CLAMP_TO_EDGE);
        gl3.glTexParameteri(GL3.GL_TEXTURE_RECTANGLE, GL3.GL_TEXTURE_WRAP_T, GL3.GL_CLAMP_TO_EDGE);
        gl3.glTexParameteri(GL3.GL_TEXTURE_RECTANGLE, GL3.GL_TEXTURE_MIN_FILTER, GL3.GL_NEAREST);
        gl3.glTexParameteri(GL3.GL_TEXTURE_RECTANGLE, GL3.GL_TEXTURE_MAG_FILTER, GL3.GL_NEAREST);

        gl3.glTexImage2D(GL3.GL_TEXTURE_RECTANGLE, 0, GL3.GL_RGBA, imageWidth, imageHeight, 0, GL3.GL_RGBA, GL3.GL_FLOAT, null);

        colorBlenderFboId = new int[1];

        gl3.glGenFramebuffers(1, colorBlenderFboId, 0);

        gl3.glBindFramebuffer(GL3.GL_FRAMEBUFFER, colorBlenderFboId[0]);

        gl3.glFramebufferTexture2D(GL3.GL_FRAMEBUFFER, GL3.GL_COLOR_ATTACHMENT0, GL3.GL_TEXTURE_RECTANGLE, colorBlenderTextureId[0], 0);
        gl3.glFramebufferTexture2D(GL3.GL_FRAMEBUFFER, GL3.GL_DEPTH_ATTACHMENT, GL3.GL_TEXTURE_RECTANGLE, depthTextureId[0], 0);
    }

    private void deleteDepthPeelingRenderTargets(GL3 gl3) {
        gl3.glDeleteFramebuffers(2, fboId, 0);
        gl3.glDeleteFramebuffers(1, colorBlenderFboId, 0);

        gl3.glDeleteTextures(2, depthTextureId, 0);
        gl3.glDeleteTextures(2, colorTextureId, 0);
        gl3.glDeleteTextures(1, colorBlenderTextureId, 0);
    }

    @Override
    public void dispose(GLAutoDrawable glad) {
        System.out.println("dispose");
    }

    @Override
    public void display(GLAutoDrawable glad) {
        System.out.println("display, passesNumber " + passesNumber);

        GL3 gl3 = glad.getGL().getGL3();

        updateCamera(gl3);
        updateProjection(gl3, imageWidth, imageHeight, base);

        geoPassesNumber = 0;

        renderDepthPeeling(gl3);

        glad.swapBuffers();

        checkError(gl3);
    }

    private void renderDepthPeeling(GL3 gl3) {
        System.out.println("render");
        /**
         * (1) Initialize min depth buffer.
         */
        //        gl3.glBindFramebuffer(GL3.GL_FRAMEBUFFER, 0);
        gl3.glBindFramebuffer(GL3.GL_FRAMEBUFFER, colorBlenderFboId[0]);
        gl3.glDrawBuffer(GL3.GL_COLOR_ATTACHMENT0);

        gl3.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        gl3.glClear(GL3.GL_COLOR_BUFFER_BIT | GL3.GL_DEPTH_BUFFER_BIT);

        gl3.glEnable(GL3.GL_DEPTH_TEST);

        dpInit.bind(gl3);
        {
            gl3.glUniform1f(dpInit.getAlphaUnLoc(), opacity);
            gl3.glUniform1i(dpInit.getEnableTextureUL(), 0);
            drawModel(gl3);

            gl3.glUniform1i(dpInit.getEnableTextureUL(), 1);
            gl3.glActiveTexture(GL3.GL_TEXTURE0);
            floorTexture.bind(gl3);
            gl3.glUniform1i(dpInit.getTexture0UL(), 0);
            drawFloor(gl3);
        }
        dpInit.unbind(gl3);

        /**
         * (2) Depth peeling + blending.
         */
        int layersNumber = (passesNumber - 1) * 2;
//        System.out.println("layersNumber: " + layersNumber);
        for (int layer = 1; layer < layersNumber; layer++) {
            System.out.println("layer " + layer);
            int currentId = layer % 2;
            int previousId = 1 - currentId;

            gl3.glBindFramebuffer(GL3.GL_FRAMEBUFFER, fboId[currentId]);
//            gl3.glBindFramebuffer(GL3.GL_FRAMEBUFFER, 0);
            gl3.glDrawBuffer(GL3.GL_COLOR_ATTACHMENT0);

            gl3.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            gl3.glClear(GL3.GL_COLOR_BUFFER_BIT | GL3.GL_DEPTH_BUFFER_BIT);

            gl3.glDisable(GL3.GL_BLEND);

            gl3.glEnable(GL3.GL_DEPTH_TEST);
            {
                dpPeel.bind(gl3);
                {
                    gl3.glActiveTexture(GL3.GL_TEXTURE0);
                    gl3.glBindTexture(GL3.GL_TEXTURE_RECTANGLE, depthTextureId[previousId]);
                    gl3.glUniform1i(dpPeel.getDepthTexUnLoc(), 0);
                    {
                        gl3.glUniform1f(dpPeel.getAlphaUnLoc(), opacity);
                        gl3.glUniform1i(dpPeel.getEnableTextureUL(), 0);
                        gl3.glUniform1i(dpPeel.getTexture0UL(), 1);
                        drawModel(gl3);

                        gl3.glUniform1i(dpPeel.getEnableTextureUL(), 1);
                        gl3.glActiveTexture(GL3.GL_TEXTURE1);
                        floorTexture.bind(gl3);

                        drawFloor(gl3);
                    }
                    gl3.glBindTexture(GL3.GL_TEXTURE_RECTANGLE, 0);
                }
                dpPeel.unbind(gl3);

                gl3.glBindFramebuffer(GL3.GL_FRAMEBUFFER, colorBlenderFboId[0]);
                gl3.glDrawBuffer(GL3.GL_COLOR_ATTACHMENT0);
            }
            gl3.glDisable(GL3.GL_DEPTH_TEST);

            gl3.glEnable(GL3.GL_BLEND);
            {
                gl3.glBlendEquation(GL3.GL_FUNC_ADD);
                gl3.glBlendFuncSeparate(GL3.GL_DST_ALPHA, GL3.GL_ONE, GL3.GL_ZERO, GL3.GL_ONE_MINUS_SRC_ALPHA);

                dpBlend.bind(gl3);
//                dpBlend.bindTextureRECT(gl3, "TempTex", colorTextureId[currentId], 0);
                gl3.glActiveTexture(GL3.GL_TEXTURE0);
                gl3.glBindTexture(GL3.GL_TEXTURE_RECTANGLE, colorTextureId[currentId]);
                gl3.glUniform1i(dpBlend.getTempTexUnLoc(), 0);
                {
//                    gl3.glCallList(quadDisplayList);
                    drawFullScreenQuad(gl3);
                }
                dpBlend.unbind(gl3);
            }
            gl3.glDisable(GL3.GL_BLEND);
        }

        /**
         * (3) Final pass.
         */
        gl3.glBindFramebuffer(GL3.GL_FRAMEBUFFER, 0);
        gl3.glDrawBuffer(GL3.GL_BACK);
        gl3.glDisable(GL3.GL_DEPTH_TEST);

        dpFinal.bind(gl3);
        {
            gl3.glUniform3f(dpFinal.getBackgroundColorUnLoc(), 1.0f, 1.0f, 1.0f);

//            dpFinal.bindTextureRECT(gl3, "ColorTex", colorBlenderTextureId[0], 0);
            gl3.glActiveTexture(GL3.GL_TEXTURE0);
            gl3.glBindTexture(GL3.GL_TEXTURE_RECTANGLE, colorBlenderTextureId[0]);
            gl3.glUniform1i(dpFinal.getColorTexUnLoc(), 0);
            {
//                gl3.glCallList(quadDisplayList);
                drawFullScreenQuad(gl3);
            }
            gl3.glBindTexture(GL3.GL_TEXTURE_RECTANGLE, 0);
        }
        dpFinal.unbind(gl3);
    }

    private void drawModel(GL3 gl3) {

        gl3.glBindBuffer(GL3.GL_ARRAY_BUFFER, modelVBO[0]);

        gl3.glBindVertexArray(modelVAO[0]);
        {
            //  Render, passing the vertex number
            gl3.glDrawArrays(GL3.GL_TRIANGLES, 0, modelVertexAttributes.length / 6);
        }
        gl3.glBindVertexArray(0);
        geoPassesNumber++;
    }

    private void drawFloor(GL3 gl3) {

        gl3.glBindBuffer(GL3.GL_ARRAY_BUFFER, floorVBO[0]);

        gl3.glBindVertexArray(floorVAO[0]);
        {
            //  Render, passing the vertex number
            gl3.glDrawArrays(GL3.GL_QUADS, 0, 4);
        }
        gl3.glBindVertexArray(0);
    }

    private void drawFullScreenQuad(GL3 gl3) {

        gl3.glBindBuffer(GL3.GL_ARRAY_BUFFER, quadVBO[0]);

        gl3.glBindVertexArray(quadVAO[0]);
        {
            //  Render, passing the vertex number
            gl3.glDrawArrays(GL3.GL_QUADS, 0, 4);
        }
        gl3.glBindVertexArray(0);
    }

    @Override
    public void reshape(GLAutoDrawable glad, int x, int y, int width, int height) {
        System.out.println("reshape");

        GL3 gl3 = glad.getGL().getGL3();

        if (imageWidth != width || imageHeight != height) {
            imageWidth = width;
            imageHeight = height;

            deleteDepthPeelingRenderTargets(gl3);
            initDepthPeelingRenderTargets(gl3);

            gl3.glBindFramebuffer(GL3.GL_FRAMEBUFFER, 0);
        }

        updateProjection(gl3, width, height, 10000);

        gl3.glViewport(0, 0, imageWidth, imageHeight);
    }

    private void updateProjection(GL3 gl3, int width, int height, int base) {

        gl3.glBindBuffer(GL3.GL_UNIFORM_BUFFER, mvpMatrixesUBO[0]);
        {
            float aspect = (float) width / (float) height;
            int size = 16 * 4;
            //  Projection Matrix
            Mat4 projectionMatrix = Jglm.orthographic(-base * aspect, base * aspect, -base, base, -base, base);

            FloatBuffer floatBuffer = GLBuffers.newDirectFloatBuffer(projectionMatrix.toFloatArray());

            gl3.glBufferSubData(GL3.GL_UNIFORM_BUFFER, 0, size, floatBuffer);
        }
    }

    private void updateCamera(GL3 gl3) {

        gl3.glBindBuffer(GL3.GL_UNIFORM_BUFFER, mvpMatrixesUBO[0]);
        {
            int size = 16 * 4;
            //  Modelview Matrix
            Mat4 modelviewMatrix = viewPole.calcMatrix();

            FloatBuffer floatBuffer = GLBuffers.newDirectFloatBuffer(modelviewMatrix.toFloatArray());

            gl3.glBufferSubData(GL3.GL_UNIFORM_BUFFER, 16 * 4, size, floatBuffer);
        }
        gl3.glBindBuffer(GL3.GL_UNIFORM_BUFFER, 0);
    }

    @Override
    public void keyPressed(KeyEvent ke) {

        Quat offset;
        float angle = 5;

        switch (ke.getKeyCode()) {

            case KeyEvent.VK_W:
                offset = Jglm.angleAxis(angle, new Vec3(1f, 0f, 0f));
//                viewPole.getCurrView().setOrient(viewPole.getCurrView().getOrient().mult(offset));
                viewPole.getCurrView().setOrient(offset.mult(viewPole.getCurrView().getOrient()));
                break;

            case KeyEvent.VK_S:
                offset = Jglm.angleAxis(-angle, new Vec3(1f, 0f, 0f));
//                viewPole.getCurrView().setOrient(viewPole.getCurrView().getOrient().mult(offset));
                viewPole.getCurrView().setOrient(offset.mult(viewPole.getCurrView().getOrient()));
                break;

            case KeyEvent.VK_A:
                offset = Jglm.angleAxis(angle, new Vec3(0f, 1f, 0f));
//                viewPole.getCurrView().setOrient(viewPole.getCurrView().getOrient().mult(offset));
                viewPole.getCurrView().setOrient(offset.mult(viewPole.getCurrView().getOrient()));
                break;

            case KeyEvent.VK_D:
                offset = Jglm.angleAxis(-angle, new Vec3(0f, 1f, 0f));
//                viewPole.getCurrView().setOrient(viewPole.getCurrView().getOrient().mult(offset));
                viewPole.getCurrView().setOrient(offset.mult(viewPole.getCurrView().getOrient()));
                break;

            case KeyEvent.VK_Q:
                base += 100;
                break;

            case KeyEvent.VK_E:
                base -= 100;
                break;
        }
        glWindow.display();
    }

    @Override
    public void keyReleased(KeyEvent ke) {
    }

    @Override
    public void mouseClicked(MouseEvent me) {
    }

    @Override
    public void mouseEntered(MouseEvent me) {
    }

    @Override
    public void mouseExited(MouseEvent me) {
    }

    @Override
    public void mousePressed(MouseEvent me) {
    }

    @Override
    public void mouseReleased(MouseEvent me) {
    }

    @Override
    public void mouseMoved(MouseEvent me) {
    }

    @Override
    public void mouseDragged(MouseEvent me) {
    }

    @Override
    public void mouseWheelMoved(MouseEvent me) {
    }

    private void checkError(GL3 gl3) {

        int error = gl3.glGetError();
        if (error != 0) {
            System.out.println("error " + error);
        }
    }
}
