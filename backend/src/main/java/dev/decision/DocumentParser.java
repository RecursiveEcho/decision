package dev.decision;

import com.fasterxml.jackson.databind.node.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import javax.imageio.ImageIO;
import javax.xml.parsers.SAXParserFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.*;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;
import org.xml.sax.helpers.DefaultHandler;

/** Parsing happens in a killable, heap-bounded child, without application credentials. */
final class DocumentParser {
  static final int TEXT_LIMIT = 32000;

  static ObjectNode isolated(byte[] bytes, String type) {
    return isolated(bytes, type, 20000);
  }

  static ObjectNode isolated(byte[] bytes, String type, long timeoutMillis) {
    Path dir = null;
    Process child = null;
    try {
      dir = Files.createTempDirectory("decision-attachment-");
      Path input = dir.resolve("input"), output = dir.resolve("result.json");
      Files.write(input, bytes);
      List<String> command =
          new ArrayList<>(
              List.of(
                  Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "-Xmx256m",
                  "-XX:MaxDirectMemorySize=32m",
                  "-Djava.awt.headless=true",
                  "-Djava.io.tmpdir=" + dir,
                  "-Duser.home=" + dir));
      String cp = System.getProperty("java.class.path");
      if (cp.endsWith(".jar") && !cp.contains(File.pathSeparator)) {
        command.addAll(List.of("-jar", cp));
      } else {
        command.addAll(List.of("-cp", cp, "dev.decision.Application"));
      }
      command.addAll(List.of("--parse-attachment", type, input.toString(), output.toString()));
      ProcessBuilder pb = new ProcessBuilder(command);
      pb.environment().clear();
      pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
      pb.redirectError(ProcessBuilder.Redirect.DISCARD);
      child = pb.start();
      if (!child.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
        child.destroyForcibly().waitFor();
        throw new ApiError(422, "ATTACHMENT_PARSE_TIMEOUT", "附件解析超时；可重试或使用较小文件", true);
      }
      if (child.exitValue() != 0
          || !Files.isRegularFile(output)
          || Files.size(output) > 16 * 1024 * 1024)
        throw new ApiError(422, "ATTACHMENT_PARSE_LIMIT", "附件损坏或超出安全解析资源限制");
      ObjectNode result = (ObjectNode) Json.parse(Files.readString(output));
      if (result.has("error"))
        throw new ApiError(422, result.path("error").asText(), result.path("message").asText());
      return result;
    } catch (ApiError e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiError(503, "ATTACHMENT_PARSE_INTERRUPTED", "附件解析被中断，可重试", true);
    } catch (Exception e) {
      throw new ApiError(422, "ATTACHMENT_PARSE_FAILED", "附件无法解析，请检查格式或重传");
    } finally {
      if (child != null && child.isAlive()) child.destroyForcibly();
      if (dir != null)
        try (var paths = Files.walk(dir)) {
          paths
              .sorted(Comparator.reverseOrder())
              .forEach(
                  p -> {
                    try {
                      Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                  });
        } catch (IOException ignored) {
        }
    }
  }

  static void child(String type, String input, String output) {
    ObjectNode result;
    try {
      result = parse(Path.of(input), type);
    } catch (ApiError e) {
      result = Json.obj("error", e.code, "message", e.getMessage());
    } catch (Exception e) {
      result = Json.obj("error", "ATTACHMENT_PARSE_FAILED", "附件无法解析或格式不受支持");
    }
    try {
      Files.writeString(Path.of(output), result.toString());
    } catch (IOException e) {
      System.exit(2);
    }
  }

  static ObjectNode parse(Path path, String type) throws Exception {
    LimitedText text = new LimitedText();
    ArrayNode images = Json.arr();
    ObjectNode coverage = Json.obj("truncated", false);
    switch (type) {
      case "png", "jpg", "jpeg" -> {
        BufferedImage image = ImageIO.read(path.toFile());
        if (image == null
            || image.getWidth() > 4096
            || image.getHeight() > 4096
            || (long) image.getWidth() * image.getHeight() > 12000000)
          throw new ApiError(422, "ATTACHMENT_IMAGE_INVALID", "图片无法解码或像素超限");
        image.flush();
      }
      case "pdf" -> pdf(path, text, images, coverage);
      case "docx", "xlsx" -> {
        Set<String> parts = validateZip(path, type);
        boolean omitted =
            parts.stream()
                .anyMatch(
                    n ->
                        type.equals("docx")
                            ? n.matches(
                                "word/(header.*|footer.*|comments.*|footnotes.*|endnotes.*|media/.*)")
                            : n.matches(
                                "xl/(drawings/.*|media/.*|charts/.*|comments.*|threadedComments/.*)"));
        coverage.put("truncated", omitted);
        ZipSecureFile.setMinInflateRatio(0.01);
        ZipSecureFile.setMaxEntrySize(8L * 1024 * 1024);
        ZipSecureFile.setMaxTextSize(32L * 1024 * 1024);
        if (type.equals("docx")) {
          try (var pkg = OPCPackage.open(path.toFile(), PackageAccess.READ);
              var doc = new XWPFDocument(pkg)) {
            for (IBodyElement item : doc.getBodyElements()) {
              if (item instanceof XWPFParagraph paragraph) text.add(paragraph.getText() + "\n");
              if (item instanceof XWPFTable table)
                for (var row : table.getRows()) {
                  for (var cell : row.getTableCells()) text.add(cell.getText() + "\t");
                  text.add("\n");
                  if (text.truncated) break;
                }
              if (text.truncated) break;
            }
            // Drawings/header/footer/embedded objects are not claimed as extracted.
            coverage.put("notice", "已读取正文段落和表格；未读取页眉页脚、批注和内嵌图片。");
          }
        } else {
          try (var pkg = OPCPackage.open(path.toFile(), PackageAccess.READ);
              var book = new XSSFWorkbook(pkg)) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            formatter.setUseCachedValuesForFormulaCells(true);
            int rows = 0;
            for (int i = 0; i < Math.min(book.getNumberOfSheets(), 20); i++) {
              Sheet sheet = book.getSheetAt(i);
              text.add("[工作表 " + sheet.getSheetName() + "]\n");
              for (Row row : sheet) {
                if (++rows > 5000) {
                  text.truncated = true;
                  break;
                }
                for (Cell cell : row) {
                  text.add(formatter.formatCellValue(cell) + "\t");
                  if (text.truncated) break;
                }
                text.add("\n");
                if (text.truncated) break;
              }
              if (text.truncated) break;
            }
            if (book.getNumberOfSheets() > 20) text.truncated = true;
            coverage.put("notice", "已读取单元格文字和公式缓存值，不执行公式；未读取图表、图片或批注。");
          }
        }
      }
      case "txt", "md", "csv" -> {
        String value =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(Files.readAllBytes(path)))
                .toString();
        if (value.indexOf('\0') >= 0)
          throw new ApiError(422, "ATTACHMENT_ENCODING", "文本需使用UTF-8编码");
        text.add(value.replaceFirst("^\\uFEFF", ""));
      }
      default -> throw new ApiError(422, "ATTACHMENT_TYPE", "不支持的附件类型");
    }
    boolean partial = coverage.path("truncated").asBoolean() || text.truncated;
    if (text.truncated) {
      coverage.put("truncated", true);
      coverage.put(
          "notice",
          coverage.path("notice").asText("") + " 文字/表格已达到读取上限，仅使用前32000字符、最多20工作表和5000行。");
    }
    return Json.obj(
        "status",
        partial ? "partial" : "ready",
        "text",
        text.value.toString(),
        "images",
        images,
        "coverage",
        coverage);
  }

  static void pdf(Path path, LimitedText text, ArrayNode images, ObjectNode coverage)
      throws Exception {
    try (PDDocument doc = Loader.loadPDF(path.toFile())) {
      if (doc.isEncrypted()) throw new ApiError(422, "ATTACHMENT_ENCRYPTED", "请先解密PDF后重新上传");
      int count = doc.getNumberOfPages(), processed = 0;
      boolean partial = count > 20;
      PDFTextStripper stripper = new PDFTextStripper();
      PDFRenderer renderer = new PDFRenderer(doc);
      renderer.setSubsamplingAllowed(true);
      boolean skippedVisuals = false;
      for (int page = 0; page < Math.min(count, 20); page++) {
        stripper.setStartPage(page + 1);
        stripper.setEndPage(page + 1);
        String value = stripper.getText(doc);
        if (value.strip().length() >= 24
            && doc.getPage(page).getResources() != null
            && doc.getPage(page).getResources().getXObjectNames().iterator().hasNext()) {
          partial = true;
          skippedVisuals = true;
        }
        text.add("[第" + (page + 1) + "页]\n" + value);
        if (value.strip().length() < 24) {
          if (images.size() >= 3) {
            partial = true;
            continue;
          }
          var box = doc.getPage(page).getCropBox();
          float longest = Math.max(box.getWidth(), box.getHeight());
          if (!Float.isFinite(longest) || longest <= 0)
            throw new ApiError(422, "ATTACHMENT_PDF_INVALID", "PDF页尺寸无效");
          float scale = Math.min(2f, 2048f / longest);
          BufferedImage image = renderer.renderImage(page, scale, ImageType.RGB);
          ByteArrayOutputStream out = new ByteArrayOutputStream();
          ImageIO.write(image, "jpg", out);
          image.flush();
          if (out.size() > 4 * 1024 * 1024)
            throw new ApiError(422, "ATTACHMENT_PARSE_LIMIT", "扫描页图像过大");
          images.add(
              Json.obj(
                  "page",
                  page + 1,
                  "dataUrl",
                  "data:image/jpeg;base64,"
                      + Base64.getEncoder().encodeToString(out.toByteArray())));
        }
        processed++;
        if (text.truncated) {
          partial = true;
          break;
        }
      }
      coverage.put("pages", count).put("processedPages", processed).put("truncated", partial);
      coverage.put(
          "notice",
          "PDF共"
              + count
              + "页，已处理"
              + processed
              + "页；最多前20页文字，低文字/扫描页最多渲染3页图像。图像理解不等于逐字原文引用。"
              + (skippedVisuals ? " 部分文字页含插图，本次只提取这些页的文字，未识别插图内容。" : ""));
    } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
      throw new ApiError(422, "ATTACHMENT_ENCRYPTED", "请先解密PDF后重新上传");
    }
  }

  static Set<String> validateZip(Path path, String type) throws Exception {
    Set<String> names = new HashSet<>();
    long total = 0;
    int entries = 0;
    SAXParserFactory factory = SAXParserFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setXIncludeAware(false);
    try (var zip = new ZipFile(path.toFile())) {
      var enumeration = zip.entries();
      while (enumeration.hasMoreElements()) {
        ZipEntry entry = enumeration.nextElement();
        String name = entry.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        if (++entries > 2048
            || !names.add(name)
            || name.contains("..")
            || name.startsWith("/")
            || lower.contains("vbaproject")
            || lower.contains("/embeddings/"))
          throw new ApiError(422, "ATTACHMENT_UNSAFE_DOCUMENT", "文档包含不支持的嵌入内容或压缩结构");
        byte[] data;
        try (var stream = zip.getInputStream(entry)) {
          data = stream.readNBytes(8 * 1024 * 1024 + 1);
        }
        total += data.length;
        if (data.length > 8 * 1024 * 1024
            || total > 32 * 1024 * 1024
            || total > Math.max(1024 * 1024, Files.size(path) * 100))
          throw new ApiError(422, "ATTACHMENT_ZIP_LIMIT", "文档解压量超出安全限制");
        if (lower.endsWith(".xml") || lower.endsWith(".rels")) {
          factory
              .newSAXParser()
              .parse(
                  new ByteArrayInputStream(data),
                  new DefaultHandler() {
                    @Override
                    public void startElement(
                        String uri, String local, String q, org.xml.sax.Attributes a)
                        throws org.xml.sax.SAXException {
                      if ("External".equalsIgnoreCase(a.getValue("TargetMode")))
                        throw new org.xml.sax.SAXException("External relationships disabled");
                      String ct = a.getValue("ContentType");
                      if (ct != null && ct.toLowerCase(Locale.ROOT).contains("macroenabled"))
                        throw new org.xml.sax.SAXException("Macros disabled");
                    }
                  });
        }
      }
    }
    if (!names.contains(type.equals("docx") ? "word/document.xml" : "xl/workbook.xml"))
      throw new ApiError(422, "ATTACHMENT_TYPE_MISMATCH", "文件内容与扩展名不匹配");
    return names;
  }

  static final class LimitedText {
    final StringBuilder value = new StringBuilder();
    boolean truncated;

    void add(String s) {
      int available = TEXT_LIMIT - value.length();
      if (s.length() > available) truncated = true;
      value.append(s, 0, Math.min(s.length(), available));
    }
  }
}
