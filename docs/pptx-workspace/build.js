const pptxgen = require('pptxgenjs');
const html2pptx = require('/Users/yunfeilu/.claude/plugins/cache/anthropic-agent-skills/document-skills/69c0b1a06741/skills/pptx/scripts/html2pptx.js');
const path = require('path');

const SLIDES_DIR = path.join(__dirname, 'slides');
const ARCH_IMG = '/Users/yunfeilu/Documents/Github/vnas-aws-iot-poc/docs/iot-pipeline-arch.png';
const AWS_LOGO = path.join(SLIDES_DIR, 'aws-logo.png');
const OUTPUT = '/Users/yunfeilu/Documents/Github/vnas-aws-iot-poc/docs/iot-data-pipeline-presentation.pptx';

// Add AWS branding to a slide (logo + footer + accent line)
function addBranding(slide, pptx, isCover = false) {
  // Bottom accent line (teal green)
  slide.addShape(pptx.shapes.RECTANGLE, {
    x: 0, y: 5.52, w: 10, h: 0.03,
    fill: { color: "0EEDAF" }
  });

  // Footer text
  slide.addText("© 2025, Amazon Web Services, Inc. or its affiliates. All rights reserved.", {
    x: 0.5, y: 5.32, w: 6, h: 0.2,
    fontSize: 6, color: "6B7280", fontFace: "Arial"
  });

  if (isCover) {
    // Large logo on cover slide (top-right area)
    slide.addImage({ path: AWS_LOGO, x: 6.8, y: 0.3, w: 2.8, h: 1.12 });
  } else {
    // Small logo on content slides (top-right corner)
    slide.addImage({ path: AWS_LOGO, x: 8.2, y: 0.15, w: 1.5, h: 0.6 });
  }
}

async function build() {
  const pptx = new pptxgen();
  pptx.layout = 'LAYOUT_16x9';
  pptx.author = 'Yunfei Lu';
  pptx.title = 'IoT Vehicle Telemetry Data Pipeline - AWS Solution Architecture';

  // Slide 1: Cover
  const { slide: slide1 } = await html2pptx(path.join(SLIDES_DIR, 'slide1-cover.html'), pptx);
  addBranding(slide1, pptx, true);

  // Slide 2: Business Scenario
  const { slide: slide2 } = await html2pptx(path.join(SLIDES_DIR, 'slide2-scenario.html'), pptx);
  addBranding(slide2, pptx);

  // Slide 3: Architecture diagram
  const { slide: slide3, placeholders: ph3 } = await html2pptx(path.join(SLIDES_DIR, 'slide3-architecture.html'), pptx);
  addBranding(slide3, pptx);
  const imgH = 3.6;
  const imgW = imgH * (1963 / 651);
  const imgX = (10 - imgW) / 2;
  const archPlaceholder = ph3.find(p => p.id === 'arch-image');
  if (archPlaceholder) {
    slide3.addImage({
      path: ARCH_IMG,
      x: imgX, y: archPlaceholder.y,
      w: imgW, h: imgH
    });
  }

  // Slide 4: Architecture Details
  const { slide: slide4 } = await html2pptx(path.join(SLIDES_DIR, 'slide4-details.html'), pptx);
  addBranding(slide4, pptx);

  // Slide 5: Cost Estimation
  const { slide: slide5, placeholders: ph5 } = await html2pptx(path.join(SLIDES_DIR, 'slide5-cost.html'), pptx);
  addBranding(slide5, pptx);

  const costTable = ph5.find(p => p.id === 'cost-table');
  if (costTable) {
    const headerOpts = { fill: { color: "0EEDAF" }, color: "09051B", bold: true, fontSize: 11, align: "left", valign: "middle" };
    const cellOpts = { color: "D1D5DB", fontSize: 11, align: "left", valign: "middle", fill: { color: "1a1030" } };
    const altOpts = { color: "D1D5DB", fontSize: 11, align: "left", valign: "middle", fill: { color: "140c25" } };
    const totalOpts = { fill: { color: "2C0152" }, color: "FF9900", bold: true, fontSize: 11, align: "left", valign: "middle" };
    slide5.addTable([
      [
        { text: "Service", options: headerOpts },
        { text: "Monthly Cost", options: headerOpts }
      ],
      [{ text: "AWS IoT Core", options: cellOpts }, { text: "~$28,600", options: cellOpts }],
      [{ text: "Amazon MSK", options: altOpts }, { text: "~$8,100", options: altOpts }],
      [{ text: "Managed Flink", options: cellOpts }, { text: "~$2,500", options: cellOpts }],
      [{ text: "Amazon S3", options: altOpts }, { text: "$1,600 - $3,200", options: altOpts }],
      [{ text: "Data Transfer", options: cellOpts }, { text: "$1,500 - $2,000", options: cellOpts }],
      [{ text: "Amazon Athena", options: altOpts }, { text: "$50 - $500", options: altOpts }],
      [
        { text: "Total", options: totalOpts },
        { text: "~$42,350 - $44,900", options: totalOpts }
      ]
    ], {
      x: costTable.x, y: costTable.y, w: costTable.w,
      colW: [2.5, 2.0],
      border: { pt: 0.5, color: "2C0152" },
      rowH: [0.38, 0.38, 0.38, 0.38, 0.38, 0.38, 0.38, 0.42]
    });
  }

  const costChart = ph5.find(p => p.id === 'cost-chart');
  if (costChart) {
    slide5.addChart(pptx.charts.PIE, [{
      name: "Cost Distribution",
      labels: ["IoT Core", "MSK", "Flink", "S3", "Transfer", "Athena"],
      values: [28600, 8100, 2500, 2400, 1750, 275]
    }], {
      ...costChart,
      showTitle: true,
      title: "Cost Distribution",
      titleColor: "FFFFFF",
      titleFontSize: 12,
      showPercent: true,
      dataLabelColor: "FFFFFF",
      dataLabelFontSize: 9,
      showLegend: true,
      legendPos: "b",
      legendFontSize: 8,
      legendColor: "D1D5DB",
      chartColors: ["FF9900", "0EEDAF", "FF706E", "F2FF85", "BC007A", "2E77FA"]
    });
  }

  // Slide 6: Optimization
  const { slide: slide6 } = await html2pptx(path.join(SLIDES_DIR, 'slide6-optimization.html'), pptx);
  addBranding(slide6, pptx);

  await pptx.writeFile({ fileName: OUTPUT });
  console.log('Presentation created:', OUTPUT);
}

build().catch(console.error);
