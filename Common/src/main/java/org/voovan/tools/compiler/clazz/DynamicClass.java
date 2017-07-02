package org.voovan.tools.compiler.clazz;

import org.voovan.tools.*;
import org.voovan.tools.compiler.DynamicCompiler;
import org.voovan.tools.log.Logger;
import org.voovan.tools.reflect.TReflect;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 动态类管理类
 *
 * @author: helyho
 * Voovan Framework.
 * WebSite: https://github.com/helyho/Voovan
 * Licence: Apache v2 License
 */
public class DynamicClass {

    //动态编译相关的对象
    private String name;
    private String className;
    private String code;
    private String javaCode;
    private Class clazz;


    private File codeFile;
    private String fileCharset;
    private long lastFileTimeStamp;

    private boolean needCompile;


    /**
     * 构造函数
     */
    public DynamicClass(String code) {
        init();
        this.code = code;
    }

    /**
     * 构造函数
     *
     * @param file    脚本文件路径
     * @param charset 脚本文件编码
     * @throws UnsupportedEncodingException
     */
    public DynamicClass(File file, String charset) throws UnsupportedEncodingException {
        init();
        this.codeFile = file;
        this.fileCharset = charset;
        this.lastFileTimeStamp = file.lastModified();
    }

    /**
     * 初始化
     */
    private void init() {
        this.name = null;
        this.code = null;
        this.clazz = Object.class;
        this.codeFile = null;
        this.needCompile = true;

        needCompile = true;

    }

    /**
     * 获得命名的名称
     *
     * @return 命名的名称
     */
    public String getName() {
        return name;
    }

    /**
     * 设置命名的名称
     *
     * @param name 命名的名称
     */
    public void setName(String name) {
        this.name = name;
    }

    public String getCode() {
        if (codeFile != null) {
            try {
                this.code = new String(TFile.loadFile(this.codeFile), this.fileCharset);
            } catch (UnsupportedEncodingException e) {
                Logger.error("Load file " + this.codeFile.getPath() + " error", e);
            }
        }

        return code;
    }

    /**
     * 设置脚本代码
     *
     * @param code 脚本代码
     */
    public void setCode(String code) {
        if (codeFile == null) {
            this.code = code;
            needCompile = true;
        } else {
            throw new RuntimeException("This function used code in file, Can't invoke this method.");
        }
    }

    /**
     * 得到实际编译的类名称
     *
     * @return 实际编译的类名称
     */
    public String getClassName() {
        return className;
    }

    /**
     * 获得编译后的 Class 对象
     *
     * @return 实际编译的类对象
     */
    public Class getClazz() {

        if (this.clazz != Object.class && codeFile != null) {
            checkFileChanged();
        }

        if (this.clazz == Object.class || needCompile) {
            try {
                compileCode();
            } catch (ReflectiveOperationException e) {
                e.printStackTrace();
                this.clazz = null;
            }
        }

        return clazz;
    }

    /**
     * 生成编译时混淆的类名
     */
    private void genClassName() {
        this.className = this.name + TString.generateShortUUID();
    }


    /**
     * 生成代码
     *
     * @return 生成 java 代码;
     */
    private String genCode() {
        getCode();
        this.name = DynamicCompiler.getClassNameFromCode(code);
        code = code.replaceAll("class[ ]+" + name, "class {{CLASSNAME}}");
        genClassName();

        this.javaCode = TString.tokenReplace(code, TObject.asMap(
                "CLASSNAME", className //类名
        ));

        return this.javaCode;
    }

    /**
     * 编译用户代码
     *
     * @return 返回编译后得到的 Class 对象
     * @throws ClassNotFoundException 反射异常
     */
    private void compileCode() throws ReflectiveOperationException {

        if (this.clazz != Object.class && codeFile != null) {
            checkFileChanged();
        }

        if (this.clazz == Object.class || needCompile) {
            synchronized (this.clazz) {
                genCode();

                DynamicCompiler compiler = new DynamicCompiler();
                if (compiler.compileCode(this.javaCode)) {
                    this.clazz = compiler.getClazz();
                    needCompile = false;
                } else {
                    Logger.simple(code);
                    throw new ReflectiveOperationException("Compile code error.");
                }
            }
        }
    }

    /**
     * 测试文件是否变更
     */
    private void checkFileChanged() {
        if (lastFileTimeStamp != this.codeFile.lastModified()) {
            this.lastFileTimeStamp = this.codeFile.lastModified();
            needCompile = true;
        }

    }
}