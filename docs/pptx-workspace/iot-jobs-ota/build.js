const pptxgen = require('pptxgenjs');
const html2pptx = require('/Users/yunfeilu/.claude/plugins/cache/anthropic-agent-skills/document-skills/69c0b1a06741/skills/pptx/scripts/html2pptx.js');
const path = require('path');

const SLIDES_DIR = path.join(__dirname, 'slides');
const AWS_LOGO = path.join(SLIDES_DIR, 'aws-logo.png');
const OUTPUT = path.join(__dirname, 'iot-jobs-ota-presentation.pptx');

// === AWS Branding ===
function addBranding(slide, pptx, isCover = false) {
  slide.addShape(pptx.shapes.RECTANGLE, {
    x: 0, y: 5.52, w: 10, h: 0.03,
    fill: { color: "0EEDAF" }
  });

  slide.addText("© 2026, Amazon Web Services, Inc. or its affiliates. All rights reserved.", {
    x: 0.5, y: 5.32, w: 6, h: 0.2,
    fontSize: 6, color: "6B7280", fontFace: "Arial"
  });

  if (isCover) {
    slide.addImage({ path: AWS_LOGO, x: 6.8, y: 0.3, w: 2.8, h: 1.12 });
  } else {
    slide.addImage({ path: AWS_LOGO, x: 8.2, y: 0.15, w: 1.5, h: 0.6 });
  }
}

// === Table styles ===
const headerOpts = { fill: { color: "0EEDAF" }, color: "09051B", bold: true, fontSize: 8, align: "center", valign: "middle" };
const cellOk = { color: "0EEDAF", fontSize: 8, align: "center", valign: "middle", fill: { color: "1a1030" } };
const cellNo = { color: "FF706E", fontSize: 8, align: "center", valign: "middle", fill: { color: "140c25" } };
const cellNa = { color: "6B7280", fontSize: 8, align: "center", valign: "middle", fill: { color: "09051B" } };
const rowLabel = { color: "FFFFFF", fontSize: 8, align: "left", valign: "middle", bold: true, fill: { color: "1a1030" } };
const rowLabelAlt = { ...rowLabel, fill: { color: "140c25" } };

// === Build ===
async function build() {
  const pptx = new pptxgen();
  pptx.layout = 'LAYOUT_16x9';
  pptx.author = 'Yunfei Lu';
  pptx.title = 'AWS IoT Jobs & OTA 升级方案';

  // --- Slide 1: Cover ---
  const { slide: slide1 } = await html2pptx(path.join(SLIDES_DIR, 'slide01-cover.html'), pptx);
  addBranding(slide1, pptx, true);

  // --- Slide 2: Section - State Machine ---
  const { slide: slide2 } = await html2pptx(path.join(SLIDES_DIR, 'slide02-section-statemachine.html'), pptx);
  addBranding(slide2, pptx);

  // --- Slide 3: State Machine Flow ---
  const { slide: slide3 } = await html2pptx(path.join(SLIDES_DIR, 'slide03-statemachine-flow.html'), pptx);
  addBranding(slide3, pptx);

  // --- Slide 4: Transition Matrix (with PptxGenJS table) ---
  const { slide: slide4, placeholders: ph4 } = await html2pptx(path.join(SLIDES_DIR, 'slide04-transition-matrix.html'), pptx);
  addBranding(slide4, pptx);

  const tablePh = ph4.find(p => p.id === 'transition-table');
  if (tablePh) {
    const states = ['QUEUED', 'IN_PROG', 'SUCCEED', 'FAILED', 'REJECT', 'TIMEOUT', 'CANCEL', 'REMOVED'];
    const ok = '\u2713';
    const no = '\u2717';

    // Build header row
    const headerRow = [
      { text: 'From \\ To', options: { ...headerOpts, align: "left" } },
      ...states.map(s => ({ text: s, options: headerOpts }))
    ];

    // Non-terminal rows (QUEUED, IN_PROGRESS) - all transitions allowed
    const nonTerminal = ['QUEUED', 'IN_PROG'];
    const terminal = ['SUCCEED', 'FAILED', 'REJECT', 'TIMEOUT', 'CANCEL', 'REMOVED'];

    const dataRows = states.map((fromState, rowIdx) => {
      const isNonTerminal = rowIdx < 2;
      const rLabel = rowIdx % 2 === 0 ? rowLabel : rowLabelAlt;
      return [
        { text: fromState, options: rLabel },
        ...states.map((toState, colIdx) => {
          if (rowIdx === colIdx) return { text: '-', options: cellNa };
          if (isNonTerminal) return { text: ok, options: cellOk };
          return { text: no, options: cellNo };
        })
      ];
    });

    slide4.addTable([headerRow, ...dataRows], {
      x: tablePh.x, y: tablePh.y, w: tablePh.w,
      colW: [0.85, 0.82, 0.82, 0.82, 0.82, 0.82, 0.82, 0.82, 0.82],
      border: { pt: 0.5, color: "2C0152" },
      rowH: Array(9).fill(0.28)
    });
  }

  // --- Slide 5: Terminal vs Non-terminal ---
  const { slide: slide5 } = await html2pptx(path.join(SLIDES_DIR, 'slide05-terminal-nonterminal.html'), pptx);
  addBranding(slide5, pptx);

  // --- Slide 6: Timeout Metrics ---
  const { slide: slide6 } = await html2pptx(path.join(SLIDES_DIR, 'slide06-timeout-metrics.html'), pptx);
  addBranding(slide6, pptx);

  // --- Slide 7: Section - OTA ---
  const { slide: slide7 } = await html2pptx(path.join(SLIDES_DIR, 'slide07-section-ota.html'), pptx);
  addBranding(slide7, pptx);

  // --- Slide 8: OTA Flow ---
  const { slide: slide8 } = await html2pptx(path.join(SLIDES_DIR, 'slide08-ota-flow.html'), pptx);
  addBranding(slide8, pptx);

  // --- Slide 9: Cloud vs Device ---
  const { slide: slide9 } = await html2pptx(path.join(SLIDES_DIR, 'slide09-cloud-vs-device.html'), pptx);
  addBranding(slide9, pptx);

  // --- Slide 10: Key Tech Points ---
  const { slide: slide10 } = await html2pptx(path.join(SLIDES_DIR, 'slide10-key-tech.html'), pptx);
  addBranding(slide10, pptx);

  // --- Slide 11: Timeline ---
  const { slide: slide11 } = await html2pptx(path.join(SLIDES_DIR, 'slide11-timeline.html'), pptx);
  addBranding(slide11, pptx);

  // --- Slide 12: Comparison ---
  const { slide: slide12 } = await html2pptx(path.join(SLIDES_DIR, 'slide12-comparison.html'), pptx);
  addBranding(slide12, pptx);

  // --- Slide 13: Best Practices ---
  const { slide: slide13 } = await html2pptx(path.join(SLIDES_DIR, 'slide13-best-practices.html'), pptx);
  addBranding(slide13, pptx);

  // --- Slide 14: Closing ---
  const { slide: slide14 } = await html2pptx(path.join(SLIDES_DIR, 'slide14-closing.html'), pptx);
  addBranding(slide14, pptx, true);

  // --- Write output ---
  await pptx.writeFile({ fileName: OUTPUT });
  console.log('Presentation created:', OUTPUT);
}

build().catch(console.error);
