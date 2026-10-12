# towerview on NixOS (saya): JDK 25 + the native libraries LWJGL's GLFW / OpenGL need at run time.
#   cd towerview && nix-shell --run './run.sh view ../towergen/layouts/H40r3.json --params visual.json'
# The headless renderer needs none of the GL libraries (only the JDK).
{ pkgs ? import <nixpkgs> {} }:
let
  libs = with pkgs; [
    libGL libglvnd
    (pkgs.libx11 or pkgs.xorg.libX11) (pkgs.libxcursor or pkgs.xorg.libXcursor)
    (pkgs.libxrandr or pkgs.xorg.libXrandr) (pkgs.libxi or pkgs.xorg.libXi)
    (pkgs.libxxf86vm or pkgs.xorg.libXxf86vm) (pkgs.libxext or pkgs.xorg.libXext)
    (pkgs.libxinerama or pkgs.xorg.libXinerama)
    wayland libxkbcommon libdecor
  ];
in pkgs.mkShell {
  packages = [ pkgs.jdk25 ];
  LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath libs;
  # LWJGL loads the GLFW bundled in its natives jar (nixpkgs' glfw 3.4 lacks LWJGL's IME extensions such as
  # glfwGetPreeditCursorRectangle); it only needs the X11 / Wayland / GL libraries above on LD_LIBRARY_PATH.
  shellHook = ''
    export JAVA_HOME=${pkgs.jdk25}/lib/openjdk
  '';
}
