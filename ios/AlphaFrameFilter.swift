//
//  ChromaKeyFilter.swift
//  MyTransparentVideoExample
//
//  Created by Quentin on 27/10/2017.
//  Copyright © 2017 Quentin Fasquel. All rights reserved.
//

import CoreImage

typealias AlphaFrameFilterError = AlphaFrameFilter.Error

final class AlphaFrameFilter: CIFilter {

    enum Error: Swift.Error {
        case buildInFilterNotFound
        case incompatibleExtents
        case invalidParameters
        case unknown
    }

    private(set) var inputImage: CIImage?
    private(set) var maskImage: CIImage?
    private(set) var outputError: Swift.Error?

    private lazy var blendFilter: CIFilter? = CIFilter(name: "CIBlendWithMask")

    override init() {
        super.init()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var outputImage: CIImage? {
        guard let inputImage = inputImage, let maskImage = maskImage else {
            outputError = Error.invalidParameters
            return nil
        }

        guard inputImage.extent == maskImage.extent else {
            outputError = Error.incompatibleExtents
            return nil
        }

        outputError = nil

        guard let filter = blendFilter else {
            outputError = Error.buildInFilterNotFound
            return nil
        }

        let outputExtent = inputImage.extent
        let backgroundImage = CIImage(color: .clear).cropped(to: outputExtent)
        filter.setValue(backgroundImage, forKey: kCIInputBackgroundImageKey)
        filter.setValue(inputImage, forKey: kCIInputImageKey)
        filter.setValue(maskImage, forKey: kCIInputMaskImageKey)
        return filter.outputImage
    }

    func process(_ inputImage: CIImage, mask maskImage: CIImage) throws -> CIImage {
        self.inputImage = inputImage
        self.maskImage = maskImage

        guard let outputImage = self.outputImage else {
            throw outputError ?? Error.unknown
        }

        return outputImage
    }
}
