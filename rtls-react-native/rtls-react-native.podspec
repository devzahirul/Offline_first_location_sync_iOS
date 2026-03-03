require 'json'
package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name         = 'rtls-react-native'
  s.version      = package['version']
  s.summary      = 'React Native bridge to RTLSyncKit (offline-first location sync, iOS)'
  s.homepage     = 'https://github.com/devzahirul/Offline_first_location_sync_iOS'
  s.license      = package['license']
  s.authors      = { 'RTLS' => '' }
  s.source       = { :git => 'https://github.com/devzahirul/Offline_first_location_sync_iOS.git', :tag => "v#{s.version}" }
  s.platforms    = { :ios => '15.0' }
  s.source_files = 'ios/**/*.{m,swift}'
  s.requires_arc = true
  s.dependency 'React-Core'
  # Host app must add the RTLSyncKit Swift package in Xcode (File > Add Package Dependencies).
end
