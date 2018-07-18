/*
 * Copyright 2018-2018 https://github.com/myoss
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.github.myoss.phoenix.code.format.eclipse;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.commons.lang3.JavaVersion;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jdt.internal.formatter.DefaultCodeFormatter;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.text.edits.TextEdit;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.github.myoss.phoenix.code.format.eclipse.imports.ImportsSorter;
import com.github.myoss.phoenix.code.format.eclipse.utils.ImportsUtils;
import com.github.myoss.phoenix.core.constants.PhoenixConstants;
import com.github.myoss.phoenix.core.exception.BizRuntimeException;
import com.google.common.io.Files;

import lombok.extern.slf4j.Slf4j;

/**
 * Java代码格式化工具 https://github.com/krasa/EclipseCodeFormatter
 *
 * @author Jerry.Chen
 * @since 2018年7月17日 下午11:11:06
 */
@Slf4j
public class JavaCodeFormatter {
    /**
     * 匹配行尾空格
     */
    public static final Pattern    TRAILING_SPACES = Pattern.compile("([^ \\t\\r\\n])[ \\t]+$", Pattern.MULTILINE);
    protected DefaultCodeFormatter defaultCodeFormatter;
    protected ImportsSorter        importsSorter;

    /**
     * Java代码格式化工具
     *
     * @param formatConfigFile EclipseCodeFormatter 格式化规则文件路径
     * @param formatConfigFileProfile EclipseCodeFormatter 格式化规则文件中的 name
     *            属性，具体使用哪个 profile
     * @param importsSorter Java import代码格式化工具
     */
    public JavaCodeFormatter(String formatConfigFile, String formatConfigFileProfile, ImportsSorter importsSorter) {
        Properties properties = new Properties();
        try (InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream(formatConfigFile)) {
            readXmlJavaSettingsFile(inputStream, properties, formatConfigFileProfile);
        } catch (IOException ex) {
            throw new BizRuntimeException("read file: " + formatConfigFile, ex);
        }
        String javaVersion = JavaVersion.JAVA_RECENT.toString();
        properties.setProperty("org.eclipse.jdt.core.compiler.source", javaVersion);
        properties.setProperty("org.eclipse.jdt.core.compiler.codegen.targetPlatform", javaVersion);
        properties.setProperty("org.eclipse.jdt.core.compiler.compliance", javaVersion);
        this.defaultCodeFormatter = new DefaultCodeFormatter(properties);
        this.importsSorter = importsSorter;
    }

    /**
     * Java代码格式化工具，格式化规则使用
     *
     * <pre>
     *  eclipse-formatter-config/Default-Formatter.xml
     *  eclipse-formatter-config/Default.importorder
     * </pre>
     *
     * @param importsSorter Java import代码格式化工具
     */
    public JavaCodeFormatter(ImportsSorter importsSorter) {
        this("eclipse-formatter-config/Default-Formatter.xml", "Default", importsSorter);
    }

    /**
     * 格式化 Java 代码
     *
     * @param fileContent 文件内容
     * @return 格式化之后的内容
     * @throws BadLocationException 异常信息
     */
    public String formatText(StringBuilder fileContent) throws BadLocationException {
        // 查找 import 位置
        int s0 = fileContent.indexOf("import ");
        if (s0 > -1) {
            int s1 = fileContent.lastIndexOf("import ");
            int s2 = fileContent.indexOf(";", s1);
            String importText = fileContent.substring(s0, s2 + 1);
            // 删除掉所有的 import
            fileContent.delete(s0, s2 + 1);

            // import 排序
            List<String> importList = ImportsUtils.trimImports(importText);
            String importTextSort = importsSorter.sort(importList);
            fileContent.insert(s0, importTextSort);
        }

        // 格式化
        String text = fileContent.toString();
        IDocument doc = new Document();
        doc.set(text);
        int length = fileContent.length();
        TextEdit edit = defaultCodeFormatter
                .format(CodeFormatter.K_COMPILATION_UNIT | CodeFormatter.F_INCLUDE_COMMENTS, text, 0, length, 0,
                        ImportsUtils.N);
        edit.apply(doc);
        String formatted = doc.get();
        Matcher matcher = TRAILING_SPACES.matcher(formatted);
        if (matcher.find()) {
            // 移除行尾空格
            return matcher.replaceAll("$1");
        }
        return formatted;
    }

    /**
     * 格式化 Java 代码
     *
     * @param filePath 文件路径
     * @return true: 格式化成功; false: 格式化失败
     */
    public boolean formatFile(String filePath) {
        log.info("starting to format by eclipse formatter: {}", filePath);
        StringBuilder fileContent = new StringBuilder();
        File sourceFile = new File(filePath);
        try {
            Files.asCharSource(sourceFile, PhoenixConstants.DEFAULT_CHARSET).copyTo(fileContent);
        } catch (IOException ex) {
            throw new BizRuntimeException("read file: " + filePath, ex);
        }

        try {
            String formatted = formatText(fileContent);
            Files.asCharSink(sourceFile, PhoenixConstants.DEFAULT_CHARSET).write(formatted);
            return true;
        } catch (Exception ex) {
            log.error("format by eclipse formatter failed: " + filePath, ex);
            return false;
        }
    }

    /**
     * 格式化 Java 代码
     *
     * @param directoryPath 文件夹路径
     * @return true: 格式化成功; false: 格式化失败
     */
    public List<String> formatDirectory(String directoryPath) {
        try {
            List<String> result = new ArrayList<>();
            java.nio.file.Files.walkFileTree(Paths.get(directoryPath), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String filePath = file.toString();
                    if (!filePath.endsWith(".java")) {
                        return FileVisitResult.CONTINUE;
                    }
                    boolean isFormat = formatFile(filePath);
                    if (!isFormat) {
                        result.add("格式化失败: " + filePath.substring(directoryPath.length()));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            return result;
        } catch (IOException ex) {
            throw new BizRuntimeException("read directory: " + directoryPath, ex);
        }
    }

    private Properties readXmlJavaSettingsFile(InputStream file, Properties properties, String profile) {
        int defaultSize = properties.size();
        if (profile == null) {
            throw new IllegalStateException("no profile selected, go to settings and select proper settings file");
        }
        boolean profileFound = false;
        try {
            // load file profiles
            org.w3c.dom.Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);
            doc.getDocumentElement().normalize();

            NodeList profiles = doc.getElementsByTagName("profile");
            if (profiles.getLength() == 0) {
                throw new IllegalStateException(
                        "loading of profile settings failed, file does not contain any profiles");
            }
            for (int temp = 0; temp < profiles.getLength(); temp++) {
                Node profileNode = profiles.item(temp);
                if (profileNode.getNodeType() == Node.ELEMENT_NODE) {
                    Element profileElement = (Element) profileNode;
                    String name = profileElement.getAttribute("name");
                    if (profile.equals(name)) {
                        profileFound = true;
                        NodeList childNodes = profileElement.getElementsByTagName("setting");
                        if (childNodes.getLength() == 0) {
                            throw new IllegalStateException(
                                    "loading of profile settings failed, profile has no settings elements");
                        }
                        for (int i = 0; i < childNodes.getLength(); i++) {
                            Node item = childNodes.item(i);
                            if (item.getNodeType() == Node.ELEMENT_NODE) {
                                Element attributeItem = (Element) item;
                                String id = attributeItem.getAttribute("id");
                                String value = attributeItem.getAttribute("value");
                                properties.setProperty(id.trim(), value.trim());
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        }
        if (!profileFound) {
            throw new IllegalStateException("profile not found in the file " + file);
        }
        if (properties.size() == defaultSize) {
            throw new IllegalStateException("no properties loaded, something is broken, file:");
        }
        return properties;
    }
}
