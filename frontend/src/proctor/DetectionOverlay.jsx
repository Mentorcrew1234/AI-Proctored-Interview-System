import { useEffect, useRef } from 'react';

/**
 * Draws the current detections over the existing camera preview. DEMO only.
 *
 * There is no second camera stream and no second detection pass: this reads the
 * boxes the running pipeline already produced for the frame and paints them on
 * a canvas sitting on top of the same `<video>` the interview already uses.
 * Nothing here feeds back into detection or recording.
 *
 * The preview is styled `object-fit: cover` and mirrored, so the same mapping
 * has to be applied here or the boxes drift away from what they describe:
 * cover scales by the *larger* ratio and centres the overflow, and the mirror
 * is a CSS transform on the canvas so drawing can stay in ordinary coordinates.
 */
export default function DetectionOverlay({ videoRef, status }) {
  const canvasRef = useRef(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    const video = videoRef.current;
    if (!canvas || !video) return;

    const cw = video.clientWidth;
    const ch = video.clientHeight;
    if (!cw || !ch) return;

    // Match the canvas to the element's displayed size, at device resolution so
    // the lines are not blurry on a high-DPI screen.
    const dpr = window.devicePixelRatio || 1;
    if (canvas.width !== Math.round(cw * dpr) || canvas.height !== Math.round(ch * dpr)) {
      canvas.width = Math.round(cw * dpr);
      canvas.height = Math.round(ch * dpr);
    }

    const ctx = canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, cw, ch);

    const vw = status.frame?.width || video.videoWidth;
    const vh = status.frame?.height || video.videoHeight;
    if (!vw || !vh) return;

    // object-fit: cover - scale by the larger ratio, centre what overflows.
    const scale = Math.max(cw / vw, ch / vh);
    const offsetX = (cw - vw * scale) / 2;
    const offsetY = (ch - vh * scale) / 2;
    const toScreen = (x, y, w, h) => [
      offsetX + x * scale,
      offsetY + y * scale,
      w * scale,
      h * scale,
    ];

    const box = (x, y, w, h, colour, label) => {
      ctx.lineWidth = 2;
      ctx.strokeStyle = colour;
      ctx.strokeRect(x, y, w, h);
      if (!label) return;

      ctx.font = '600 11px "Segoe UI", system-ui, sans-serif';
      const padding = 4;
      const textWidth = ctx.measureText(label).width;
      const labelY = Math.max(0, y - 16);
      ctx.fillStyle = colour;
      ctx.fillRect(x, labelY, textWidth + padding * 2, 16);
      ctx.fillStyle = '#fff';
      // Undo the mirror for the text only, so labels read the right way round.
      ctx.save();
      ctx.translate(x + padding + textWidth / 2, labelY + 12);
      ctx.scale(-1, 1);
      ctx.textAlign = 'center';
      ctx.fillText(label, 0, 0);
      ctx.restore();
    };

    // Faces: landmark extents, normalised 0..1 against the frame.
    (status.faceBoxes ?? []).forEach((f, i) => {
      const [x, y, w, h] = toScreen(f.x * vw, f.y * vh, f.w * vw, f.h * vh);
      box(x, y, w, h, '#2f5bd8', `Face ${i + 1}`);
    });

    // People and phones: COCO-SSD boxes, already in video pixels.
    (status.personBoxes ?? []).forEach((p) => {
      const [x, y, w, h] = toScreen(...p.bbox);
      box(x, y, w, h, '#12704a', `Person ${Math.round(p.score * 100)}%`);
    });

    if (status.phoneBox) {
      const [x, y, w, h] = toScreen(...status.phoneBox);
      const score = status.phoneScore != null ? ` ${Math.round(status.phoneScore * 100)}%` : '';
      box(x, y, w, h, '#b3261e', `Phone${score}`);
    }
  }, [videoRef, status]);

  return <canvas ref={canvasRef} className="detection-overlay" aria-hidden="true" />;
}
