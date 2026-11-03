import UIKit
import SwiftUI
import Shared

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Self.Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

struct ContentView: View {
    @StateObject private var desktop = DesktopConnection()
    @State private var showDesktop = false

    var body: some View {
        ComposeView()
            .ignoresSafeArea()
            .safeAreaInset(edge: .bottom) {
                HStack {
                    Text(desktop.paired ? desktop.status : "Desktop not paired")
                        .font(.caption).lineLimit(1)
                    Spacer()
                    Button("Desktop") { showDesktop = true }
                }.padding(10).background(.regularMaterial)
            }
            .sheet(isPresented: $showDesktop) {
                DesktopConnectionPanel(connection: desktop)
            }
            .task {
                while !Task.isCancelled {
                    await desktop.refresh()
                    do { try await Task.sleep(for: .seconds(10)) }
                    catch { return }
                }
            }
    }
}