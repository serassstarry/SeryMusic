const error = new URLSearchParams(location.search).get("error");
if (error) {
  const p = document.getElementById("error");
  p.textContent = error === "locked"
    ? "Demasiados intentos. Espera 5 minutos."
    : "Contraseña incorrecta.";
  p.hidden = false;
}
