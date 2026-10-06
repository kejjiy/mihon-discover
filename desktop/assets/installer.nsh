!macro customWelcomePage
  !insertmacro MUI_PAGE_WELCOME
!macroend

; Install for the current user, without offering an administrator-only mode.
!macro customInstallMode
  StrCpy $isForceCurrentInstall "1"
!macroend
