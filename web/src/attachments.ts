import type { StoredFile } from "./mediaStorage";
export const MAX_FILES = 4,
  MAX_FILE_BYTES = 8 * 1024 * 1024,
  MAX_TOTAL_BYTES = 16 * 1024 * 1024;
export const FILE_ACCEPT =
  ".png,.jpg,.jpeg,.webp,.pdf,.docx,.xlsx,.txt,.md,.csv";
const documentTypes: Record<string, string> = {
  pdf: "application/pdf",
  docx: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  xlsx: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  txt: "text/plain",
  md: "text/markdown",
  csv: "text/csv",
};
export function checkFileLimits(files: { size: number }[]) {
  if (files.length > MAX_FILES) throw new Error("一次最多添加 4 个附件。");
  if (files.some((file) => file.size > MAX_FILE_BYTES))
    throw new Error("单个附件不能超过 8 MiB。");
  if (files.reduce((sum, file) => sum + file.size, 0) > MAX_TOTAL_BYTES)
    throw new Error("附件合计不能超过 16 MiB。");
}
export async function prepareFile(file: File): Promise<StoredFile> {
  checkFileLimits([file]);
  if (!file.size) throw new Error(`“${file.name}”是空文件。`);
  const ext = file.name.split(".").pop()?.toLowerCase() || "";
  let blob: Blob,
    name = file.name;
  const imageType = (
    {
      png: "image/png",
      jpg: "image/jpeg",
      jpeg: "image/jpeg",
      webp: "image/webp",
    } as Record<string, string>
  )[ext];
  if (
    imageType ||
    ["image/png", "image/jpeg", "image/webp"].includes(file.type)
  ) {
    let image: ImageBitmap;
    try {
      image = await createImageBitmap(file);
    } catch {
      throw new Error(`无法读取图片“${name}”，请选择 PNG、JPEG 或 WebP。`);
    }
    try {
      const scale = Math.min(
        1,
        4096 / image.width,
        4096 / image.height,
        Math.sqrt(12000000 / (image.width * image.height)),
      );
      const canvas = document.createElement("canvas");
      canvas.width = Math.max(1, Math.floor(image.width * scale));
      canvas.height = Math.max(1, Math.floor(image.height * scale));
      const ctx = canvas.getContext("2d");
      if (!ctx) throw new Error("浏览器无法处理这张图片。");
      ctx.drawImage(image, 0, 0, canvas.width, canvas.height);
      const mime =
        (imageType || file.type) === "image/jpeg" ? "image/jpeg" : "image/png";
      blob = await new Promise<Blob>((resolve, reject) =>
        canvas.toBlob(
          (value) =>
            value
              ? resolve(value)
              : reject(new Error("图片转换失败，请换一张图片。")),
          mime,
          0.92,
        ),
      );
      name = `${file.name.replace(/\.[^.]*$/, "") || "图片"}.${mime === "image/jpeg" ? "jpg" : "png"}`;
    } finally {
      image.close();
    }
  } else if (documentTypes[ext])
    blob = new Blob([file], { type: documentTypes[ext] });
  else
    throw new Error(
      "支持 PNG、JPEG、WebP 图片和 PDF、DOCX、XLSX、TXT、MD、CSV 文件。",
    );
  checkFileLimits([blob]);
  return {
    id: crypto.randomUUID(),
    name,
    mimeType: blob.type,
    size: blob.size,
    kind: blob.type.startsWith("image/") ? "image" : "document",
    blob,
  };
}
export function toDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(new Error("无法读取本机暂存附件。"));
    reader.readAsDataURL(blob);
  });
}
export const fileSize = (size: number) =>
  size < 1024 * 1024
    ? `${Math.max(1, Math.ceil(size / 1024))} KB`
    : `${(size / 1024 / 1024).toFixed(1)} MB`;
