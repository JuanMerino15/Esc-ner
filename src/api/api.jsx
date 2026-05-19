import axios from "axios";

const api = axios.create({
  baseURL: "http://localhost:8083"  // 👈 sin corchetes ni () ni /api
});

export async function uploadFile(file) {
  const form = new FormData();
  form.append("file", file, file.name);

  const resp = await api.post("/api/scan", form, {
    headers: { "Content-Type": "multipart/form-data" },
    timeout: 120000
  });
  return resp.data;
}
