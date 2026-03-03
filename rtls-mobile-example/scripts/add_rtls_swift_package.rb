#!/usr/bin/env ruby
# Run this script AFTER `pod install` to add the RTLSyncKit Swift package to the Pods project.
# Usage: from repo root: ruby rtls-mobile-example/scripts/add_rtls_swift_package.rb
#    or from rtls-mobile-example: ruby scripts/add_rtls_swift_package.rb

app_root = File.dirname(File.dirname(File.absolute_path(__FILE__)))
ios_dir = File.join(app_root, 'ios')
pbxproj_path = File.join(ios_dir, 'Pods', 'Pods.xcodeproj', 'project.pbxproj')
unless File.exist?(pbxproj_path)
  puts "Error: #{pbxproj_path} not found. Run 'pod install' in ios/ first."
  exit 1
end

# From ios/Pods to repo root (where Package.swift lives)
rtls_relative_path = '../../..'
pkg_ref_id = 'RTLSPKGREF001'
pkg_dep_id = 'RTLSPKGDEP001'

pbx = File.read(pbxproj_path)
if pbx.include?(pkg_ref_id)
  puts "RTLSyncKit Swift package already added to Pods project."
  exit 0
end

# Add package reference to PBXProject
pbx.sub!(/(\s+mainGroup = 46EB2E00000010;)/, "\n\t\t\tpackageReferences = (\n\t\t\t\t#{pkg_ref_id} /* RTLSyncKit */,\n\t\t\t);\n\\1")

# Add packageProductDependencies to rtls-react-native target
marker = 'productReference = 7FFED08BA39235438EF16F9A8743B06E /* rtls-react-native */;'
pbx.sub!(
  /(#{Regexp.escape(marker)}\s+productType = "com.apple.product-type.library.static";)/,
  "\\1\n\t\t\tpackageProductDependencies = (\n\t\t\t\t#{pkg_dep_id} /* RTLSyncKit */,\n\t\t\t);"
)

# Append XCLocalSwiftPackageReference and XCSwiftPackageProductDependency sections
swift_pkg_section = <<~SECTION
  \t/* Begin XCLocalSwiftPackageReference section */
  \t\t#{pkg_ref_id} /* RTLSyncKit */ = {
  \t\t\tisa = XCLocalSwiftPackageReference;
  \t\t\trelativePath = #{rtls_relative_path};
  \t\t};
  \t/* End XCLocalSwiftPackageReference section */

  \t/* Begin XCSwiftPackageProductDependency section */
  \t\t#{pkg_dep_id} /* RTLSyncKit */ = {
  \t\t\tisa = XCSwiftPackageProductDependency;
  \t\t\tpackage = #{pkg_ref_id} /* RTLSyncKit */;
  \t\t\tproductName = RTLSyncKit;
  \t\t};
  \t/* End XCSwiftPackageProductDependency section */
SECTION
pbx.sub!(/(\t\};\s+rootObject = )/m, "#{swift_pkg_section}\\1")

File.write(pbxproj_path, pbx)
puts "RTLS: Added RTLSyncKit Swift package to Pods project. You can now build the app."
