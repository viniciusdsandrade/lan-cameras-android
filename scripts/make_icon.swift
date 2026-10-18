#!/usr/bin/env swift
// Gera o ícone adaptativo do app Android (o mesmo desenho dos apps do Mac e do iPhone) por desenho
// vetorial, sem imagens de partida.
//
//   swift scripts/make_icon.swift [pasta-res]        (padrão: app/src/main/res)
//
// O ícone adaptativo tem 108 dp e o sistema mostra só o miolo (a máscara pode ser círculo, gota,
// quadrado arredondado…); a zona segura é o círculo de 66 dp no centro. Por isso a lente e o LED
// ficam dentro dela, em três camadas: frente (transparente), fundo (azul-marinho) e monocromática
// (para o ícone temático do Android 13+). Cada densidade é desenhada nos próprios pixels.
import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

// MARK: - Cores

func cor(_ hex: UInt32, _ a: CGFloat = 1) -> CGColor {
    CGColor(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255, alpha: a)
}

let espaco = CGColorSpace(name: CGColorSpace.sRGB)!

func gradiente(_ cores: [CGColor], _ posicoes: [CGFloat]) -> CGGradient {
    CGGradient(colorsSpace: espaco, colors: cores as CFArray, locations: posicoes)!
}

// MARK: - Formas

/// Superelipse |x|^n + |y|^n = 1: o "squircle" do macOS (curvatura contínua, sem o canto circular de um retângulo).
func squircle(centro: CGPoint, raio: CGFloat, n: Double = 5) -> CGPath {
    let p = CGMutablePath()
    let passos = 720
    for i in 0...passos {
        let t = Double(i) / Double(passos) * 2 * .pi
        let c = cos(t), s = sin(t)
        let x = pow(abs(c), 2 / n) * (c < 0 ? -1 : 1)
        let y = pow(abs(s), 2 / n) * (s < 0 ? -1 : 1)
        let ponto = CGPoint(x: centro.x + CGFloat(x) * raio, y: centro.y + CGFloat(y) * raio)
        if i == 0 { p.move(to: ponto) } else { p.addLine(to: ponto) }
    }
    p.closeSubpath()
    return p
}

func circulo(_ centro: CGPoint, _ raio: CGFloat) -> CGPath {
    CGPath(ellipseIn: CGRect(x: centro.x - raio, y: centro.y - raio, width: raio * 2, height: raio * 2), transform: nil)
}

func anel(_ centro: CGPoint, externo: CGFloat, interno: CGFloat) -> CGPath {
    let p = CGMutablePath()
    p.addPath(circulo(centro, externo))
    p.addPath(circulo(centro, interno))
    return p
}

/// A sombra do Core Graphics é medida em pixels e ignora a escala do contexto: sem converter da grade de
/// 1024, a de 30 px cobriria o ícone de 16 px inteiro (uma caixa cinza em volta dele).
func sombra(_ ctx: CGContext, _ escala: CGFloat, dy: CGFloat, desfoque: CGFloat, alfa: CGFloat) {
    ctx.setShadow(offset: CGSize(width: 0, height: dy * escala), blur: desfoque * escala, color: cor(0x000000, alfa))
}

// MARK: - Desenho (espaço de 1024 x 1024 = 108 dp, origem embaixo à esquerda)

/// Fundo: o azul-marinho dos outros ícones, até as bordas, com a luz azul atrás da lente.
func desenharFundo(_ ctx: CGContext) {
    ctx.drawLinearGradient(gradiente([cor(0x45649C), cor(0x213862), cor(0x0B1426)], [0, 0.5, 1]),
                           start: CGPoint(x: 512, y: 1024), end: CGPoint(x: 512, y: 0), options: [])
    ctx.drawRadialGradient(gradiente([cor(0x3D7BFF, 0.34), cor(0x3D7BFF, 0)], [0, 1]),
                           startCenter: CGPoint(x: 512, y: 512), startRadius: 0,
                           endCenter: CGPoint(x: 512, y: 512), endRadius: 470, options: [])
    ctx.drawLinearGradient(gradiente([cor(0xFFFFFF, 0.12), cor(0xFFFFFF, 0)], [0, 1]),
                           start: CGPoint(x: 512, y: 1024), end: CGPoint(x: 512, y: 600), options: [])
}

/// Frente: a lente (a do Mac, reduzida para caber na zona segura) e o LED de "ao vivo" na diagonal.
func desenharFrente(_ ctx: CGContext, pixels: Int) {
    ctx.setAllowsAntialiasing(true)
    ctx.interpolationQuality = .high
    let miudo = pixels < 160
    // a lente do Mac tem raio ~303 em torno de (512, 500); aqui 0,78 dela, centrada no meio
    let reducao: CGFloat = 0.78
    let escala = CGFloat(pixels) / 1024 * reducao
    ctx.saveGState()
    ctx.translateBy(x: 512, y: 512)
    ctx.scaleBy(x: reducao, y: reducao)
    ctx.translateBy(x: -512, y: -500)
    // A lente inteira é desenhada em 94% em torno do centro: folga para o LED e para a borda
    let c = CGPoint(x: 512, y: 500)
    ctx.saveGState()
    ctx.translateBy(x: c.x, y: c.y)
    ctx.scaleBy(x: 0.94, y: 0.94)
    ctx.translateBy(x: -c.x, y: -c.y)

    // Base da lente: anel escuro que dá profundidade
    ctx.saveGState()
    sombra(ctx, escala, dy: -10, desfoque: 24, alfa: 0.55)
    ctx.addPath(circulo(c, 322))
    ctx.setFillColor(cor(0x0A101C))
    ctx.fillPath()
    ctx.restoreGState()
    ctx.saveGState()
    ctx.addPath(circulo(c, 322))
    ctx.clip()
    ctx.drawLinearGradient(gradiente([cor(0x2B3C5E), cor(0x0A101C)], [0, 1]),
                           start: CGPoint(x: 512, y: 822), end: CGPoint(x: 512, y: 178), options: [])
    ctx.restoreGState()

    // Aro metálico
    ctx.saveGState()
    ctx.addPath(anel(c, externo: 296, interno: 252))
    ctx.clip(using: .evenOdd)
    ctx.drawLinearGradient(gradiente([cor(0xF6F9FD), cor(0xA9B5C7), cor(0x5E6C83), cor(0xD3DCE8)], [0, 0.38, 0.66, 1]),
                           start: CGPoint(x: 280, y: 790), end: CGPoint(x: 744, y: 210), options: [])
    ctx.restoreGState()
    if !miudo {
        // fio de sombra entre o aro e o vidro
        ctx.setStrokeColor(cor(0x000000, 0.75))
        ctx.setLineWidth(8)
        ctx.addPath(circulo(c, 250))
        ctx.strokePath()
    }

    // Vidro da lente
    ctx.saveGState()
    ctx.addPath(circulo(c, 246))
    ctx.clip()
    ctx.drawRadialGradient(gradiente([cor(0x22407A), cor(0x0D1B38), cor(0x03070F)], [0, 0.62, 1]),
                           startCenter: CGPoint(x: c.x - 50, y: c.y + 50), startRadius: 0,
                           endCenter: c, endRadius: 250, options: [])
    if !miudo {
        // anéis do conjunto óptico
        for (raio, alfa, largura) in [(196.0, 0.34, 4.0), (150.0, 0.26, 3.0), (108.0, 0.22, 3.0)] as [(CGFloat, CGFloat, CGFloat)] {
            ctx.setStrokeColor(cor(0x5E9BFF, alfa))
            ctx.setLineWidth(largura)
            ctx.addPath(circulo(c, raio))
            ctx.strokePath()
        }
        // brilho esverdeado do tratamento antirreflexo
        ctx.drawRadialGradient(gradiente([cor(0x2EE6C8, 0), cor(0x2EE6C8, 0.20), cor(0x2EE6C8, 0)], [0, 0.55, 1]),
                               startCenter: c, startRadius: 120, endCenter: c, endRadius: 232, options: [])
    }
    // pupila
    ctx.drawRadialGradient(gradiente([cor(0x000000, 0.95), cor(0x000000, 0)], [0, 1]),
                           startCenter: c, startRadius: 0, endCenter: c, endRadius: 96, options: [])
    // luz azul que entra por baixo à direita
    ctx.drawRadialGradient(gradiente([cor(0x7FB2FF, 0.42), cor(0x7FB2FF, 0)], [0, 1]),
                           startCenter: CGPoint(x: c.x + 108, y: c.y - 112), startRadius: 0,
                           endCenter: CGPoint(x: c.x + 108, y: c.y - 112), endRadius: 150, options: [])
    // reflexo grande, em cima à esquerda
    ctx.saveGState()
    ctx.translateBy(x: c.x - 82, y: c.y + 112)
    ctx.rotate(by: 0.62)
    ctx.scaleBy(x: 1.55, y: 0.78)
    ctx.drawRadialGradient(gradiente([cor(0xFFFFFF, 0.55), cor(0xFFFFFF, 0.12), cor(0xFFFFFF, 0)], [0, 0.55, 1]),
                           startCenter: .zero, startRadius: 0, endCenter: .zero, endRadius: 116, options: [])
    ctx.restoreGState()
    // ponto de luz
    ctx.drawRadialGradient(gradiente([cor(0xFFFFFF, 0.95), cor(0xFFFFFF, 0)], [0, 1]),
                           startCenter: CGPoint(x: c.x - 118, y: c.y + 128), startRadius: 0,
                           endCenter: CGPoint(x: c.x - 118, y: c.y + 128), endRadius: 30, options: [])
    ctx.restoreGState()
    ctx.restoreGState()                // fim da escala da lente

    ctx.restoreGState()

    // LED: a 285 px do centro, a 45 graus; com o raio de 24 px fica dentro da zona segura (313 px)
    let led = CGPoint(x: 512 + 201, y: 512 + 201)
    let raio: CGFloat = 24
    ctx.drawRadialGradient(gradiente([cor(0x3DFF8C, 0.55), cor(0x3DFF8C, 0)], [0, 1]),
                           startCenter: led, startRadius: 0, endCenter: led, endRadius: 64, options: [])
    ctx.saveGState()
    ctx.addPath(circulo(led, raio))
    ctx.clip()
    ctx.drawRadialGradient(gradiente([cor(0xC8FFE0), cor(0x2EE57A), cor(0x0E8F45)], [0, 0.45, 1]),
                           startCenter: CGPoint(x: led.x - 6, y: led.y + 7), startRadius: 0,
                           endCenter: led, endRadius: raio + 5, options: [])
    ctx.restoreGState()
}

// MARK: - Saída

func contexto(_ pixels: Int, opaco: Bool) -> CGContext {
    CGContext(data: nil, width: pixels, height: pixels, bitsPerComponent: 8, bytesPerRow: pixels * 4, space: espaco,
              bitmapInfo: (opaco ? CGImageAlphaInfo.noneSkipLast : CGImageAlphaInfo.premultipliedLast).rawValue)!
}

/// A camada monocromática só vale pelo alfa: branco, com a opacidade seguindo a frente e o brilho
/// dela (o aro aparece forte, o vidro escuro fica translúcido).
func monocromatica(_ frente: CGContext, _ pixels: Int) -> CGImage {
    let saida = contexto(pixels, opaco: false)
    let origem = frente.data!.assumingMemoryBound(to: UInt8.self)
    let destino = saida.data!.assumingMemoryBound(to: UInt8.self)
    for i in 0..<(pixels * pixels) {
        let a = Double(origem[4 * i + 3]) / 255
        guard a > 0 else { continue }
        // valores pré-multiplicados: a cor real é cor / alfa
        let r = Double(origem[4 * i]) / 255 / a, g = Double(origem[4 * i + 1]) / 255 / a, b = Double(origem[4 * i + 2]) / 255 / a
        let luz = min(1, 0.2126 * r + 0.7152 * g + 0.0722 * b)
        let alfa = UInt8(max(0, min(255, (a * (0.35 + 0.65 * luz)) * 255)))
        destino[4 * i] = alfa; destino[4 * i + 1] = alfa; destino[4 * i + 2] = alfa; destino[4 * i + 3] = alfa
    }
    return saida.makeImage()!
}

func gravarPNG(_ img: CGImage, _ url: URL) {
    let d = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(d, img, nil)
    CGImageDestinationFinalize(d)
}

let res = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "app/src/main/res", isDirectory: true)
let densidades: [(String, Int)] = [("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)]
for (nome, px) in densidades {
    let pasta = res.appendingPathComponent("mipmap-\(nome)", isDirectory: true)
    try FileManager.default.createDirectory(at: pasta, withIntermediateDirectories: true)
    let fundo = contexto(px, opaco: true)
    fundo.scaleBy(x: CGFloat(px) / 1024, y: CGFloat(px) / 1024)
    desenharFundo(fundo)
    gravarPNG(fundo.makeImage()!, pasta.appendingPathComponent("ic_launcher_background.png"))
    let frente = contexto(px, opaco: false)
    frente.scaleBy(x: CGFloat(px) / 1024, y: CGFloat(px) / 1024)
    desenharFrente(frente, pixels: px)
    gravarPNG(frente.makeImage()!, pasta.appendingPathComponent("ic_launcher_foreground.png"))
    gravarPNG(monocromatica(frente, px), pasta.appendingPathComponent("ic_launcher_monochrome.png"))
}
let adaptativo = res.appendingPathComponent("mipmap-anydpi-v26", isDirectory: true)
try FileManager.default.createDirectory(at: adaptativo, withIntermediateDirectories: true)
try """
<?xml version="1.0" encoding="utf-8"?>
<!-- Gerado por scripts/make_icon.swift: o mesmo desenho dos apps do Mac e do iPhone. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
</adaptive-icon>

""".write(to: adaptativo.appendingPathComponent("ic_launcher.xml"), atomically: true, encoding: .utf8)
print("ícone adaptativo gerado em \(res.path)")
